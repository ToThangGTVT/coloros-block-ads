//! Root signing stays in Android Keystore. Only public DER and signatures cross JNI.
use rcgen::{
    BasicConstraints, CertificateParams, DnType, ExtendedKeyUsagePurpose, IsCa, Issuer, KeyPair,
    KeyUsagePurpose, PublicKeyData, SignatureAlgorithm, SigningKey, PKCS_ECDSA_P256_SHA256,
};
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig,
};
use std::{
    collections::HashMap,
    io,
    sync::{Arc, Mutex, OnceLock, RwLock},
    time::{Duration, Instant, SystemTime},
};

pub trait CaSigner: SigningKey + Send + Sync {}
impl<T: SigningKey + Send + Sync> CaSigner for T {}
#[derive(Clone)]
pub struct SharedSigner(pub Arc<dyn CaSigner>);
impl PublicKeyData for SharedSigner {
    fn der_bytes(&self) -> &[u8] {
        self.0.der_bytes()
    }
    fn algorithm(&self) -> &'static SignatureAlgorithm {
        self.0.algorithm()
    }
}
impl SigningKey for SharedSigner {
    fn sign(&self, msg: &[u8]) -> Result<Vec<u8>, rcgen::Error> {
        self.0.sign(msg)
    }
}
struct Leaf {
    config: Arc<ServerConfig>,
    created: Instant,
}
pub struct Authority {
    der: CertificateDer<'static>,
    signer: SharedSigner,
    leaves: Mutex<HashMap<String, Leaf>>,
}
static AUTHORITY: OnceLock<RwLock<Option<Arc<Authority>>>> = OnceLock::new();
pub fn authority() -> Option<Arc<Authority>> {
    AUTHORITY
        .get_or_init(|| RwLock::new(None))
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}
pub fn set(signer: Option<Arc<dyn CaSigner>>, der: Vec<u8>) -> bool {
    let value = match signer {
        None => None,
        Some(signer) => {
            if der.len() > 8192 || signer.der_bytes().len() != 65 {
                return false;
            }
            let cert = CertificateDer::from(der);
            if Issuer::from_ca_cert_der(&cert, SharedSigner(signer.clone())).is_err() {
                return false;
            }
            Some(Arc::new(Authority {
                der: cert,
                signer: SharedSigner(signer),
                leaves: Mutex::new(HashMap::new()),
            }))
        }
    };
    *AUTHORITY
        .get_or_init(|| RwLock::new(None))
        .write()
        .unwrap_or_else(|e| e.into_inner()) = value;
    super::changed();
    true
}
pub fn generate(signer: Arc<dyn CaSigner>) -> io::Result<Vec<u8>> {
    if signer.der_bytes().len() != 65 || signer.der_bytes()[0] != 4 {
        return Err(io::ErrorKind::InvalidInput.into());
    }
    let mut params = CertificateParams::default();
    params
        .distinguished_name
        .push(DnType::CommonName, "BlockAds Local CA");
    params.is_ca = IsCa::Ca(BasicConstraints::Constrained(0));
    params.key_usages = vec![KeyUsagePurpose::KeyCertSign, KeyUsagePurpose::CrlSign];
    params.not_before = (SystemTime::now() - Duration::from_secs(86400)).into();
    params.not_after = (SystemTime::now() + Duration::from_secs(5 * 365 * 86400)).into();
    Ok(params
        .self_signed(&SharedSigner(signer))
        .map_err(io::Error::other)?
        .der()
        .to_vec())
}
impl Authority {
    pub fn server(&self, domain: &str) -> io::Result<Arc<ServerConfig>> {
        if !super::valid_domain(domain) {
            return Err(io::ErrorKind::InvalidInput.into());
        }
        let mut leaves = self.leaves.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(leaf) = leaves
            .get(domain)
            .filter(|leaf| leaf.created.elapsed() < Duration::from_secs(86400))
        {
            return Ok(leaf.config.clone());
        }
        if leaves.len() >= 128 {
            leaves.retain(|_, leaf| leaf.created.elapsed() < Duration::from_secs(86400));
        }
        if leaves.len() >= 128 {
            if let Some(old) = leaves
                .iter()
                .min_by_key(|(_, v)| v.created)
                .map(|(k, _)| k.clone())
            {
                leaves.remove(&old);
            }
        }
        let issuer =
            Issuer::from_ca_cert_der(&self.der, self.signer.clone()).map_err(io::Error::other)?;
        let key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).map_err(io::Error::other)?;
        let mut params =
            CertificateParams::new(vec![domain.to_owned()]).map_err(io::Error::other)?;
        params.distinguished_name.push(DnType::CommonName, domain);
        params.not_before = (SystemTime::now() - Duration::from_secs(3600)).into();
        params.not_after = (SystemTime::now() + Duration::from_secs(7 * 86400)).into();
        params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
        params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
        let leaf = params.signed_by(&key, &issuer).map_err(io::Error::other)?;
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key.serialize_der()));
        let mut config =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_safe_default_protocol_versions()
                .map_err(io::Error::other)?
                .with_no_client_auth()
                .with_single_cert(vec![leaf.der().clone()], key)
                .map_err(io::Error::other)?;
        config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
        let config = Arc::new(config);
        leaves.insert(
            domain.into(),
            Leaf {
                config: config.clone(),
                created: Instant::now(),
            },
        );
        Ok(config)
    }
}
