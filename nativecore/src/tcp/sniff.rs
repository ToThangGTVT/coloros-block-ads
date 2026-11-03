//! Bounded parsers for fragmented TLS records and HTTP request headers. Never log input.
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt};
#[derive(Debug, PartialEq)]
pub enum Parsed {
    NeedMore,
    Unknown,
    Tls(String),
    Http { host: String, url: String },
}
fn host(text: &str) -> Option<String> {
    let value = text.trim().trim_end_matches('.').to_ascii_lowercase();
    if value.is_empty()
        || value.len() > 253
        || !value.bytes().all(|c| {
            c.is_ascii_alphanumeric() || matches!(c, b'-' | b'.' | b'_' | b':' | b'[' | b']')
        })
    {
        return None;
    }
    Some(value)
}
pub fn parse(bytes: &[u8]) -> Parsed {
    if bytes.is_empty() {
        return Parsed::NeedMore;
    }
    if bytes[0] == 22 {
        return tls(bytes);
    }
    if !bytes[0].is_ascii_uppercase() {
        return Parsed::Unknown;
    }
    let Some(end) = bytes.windows(4).position(|w| w == b"\r\n\r\n") else {
        return Parsed::NeedMore;
    };
    let Ok(headers) = std::str::from_utf8(&bytes[..end]) else {
        return Parsed::Unknown;
    };
    let mut lines = headers.split("\r\n");
    let mut first = lines.next().unwrap_or("").split_whitespace();
    let method = first.next().unwrap_or("");
    let path = first.next().unwrap_or("");
    if !matches!(
        method,
        "GET" | "POST" | "HEAD" | "PUT" | "DELETE" | "OPTIONS" | "PATCH" | "CONNECT" | "TRACE"
    ) || !first.next().is_some_and(|v| v.starts_with("HTTP/1."))
    {
        return Parsed::Unknown;
    }
    let mut authority = None;
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("host") {
                if authority.is_some() {
                    return Parsed::Unknown;
                }
                authority = host(value);
            }
        }
    }
    let Some(authority) = authority else {
        return Parsed::Unknown;
    };
    let domain = if authority.starts_with('[') {
        authority
            .split(']')
            .next()
            .unwrap_or("")
            .trim_start_matches('[')
            .to_owned()
    } else {
        authority.split(':').next().unwrap_or("").to_owned()
    };
    let url = if path.starts_with('/') {
        format!("http://{authority}{path}")
    } else if path.starts_with("http://") {
        path.to_owned()
    } else {
        return Parsed::Unknown;
    };
    Parsed::Http { host: domain, url }
}
fn u16_at(bytes: &[u8], at: usize) -> Option<usize> {
    Some(u16::from_be_bytes(bytes.get(at..at + 2)?.try_into().ok()?) as usize)
}
fn tls(bytes: &[u8]) -> Parsed {
    let mut handshake = Vec::with_capacity(bytes.len());
    let mut offset = 0;
    while offset < bytes.len() {
        if bytes.len() - offset < 5 {
            return Parsed::NeedMore;
        }
        if bytes[offset] != 22 || bytes[offset + 1] != 3 {
            return Parsed::Unknown;
        }
        let len = u16_at(bytes, offset + 3).unwrap_or(0);
        if len == 0 {
            return Parsed::Unknown;
        }
        if offset + 5 + len > bytes.len() {
            return Parsed::NeedMore;
        }
        handshake.extend_from_slice(&bytes[offset + 5..offset + 5 + len]);
        offset += 5 + len;
        if handshake.len() < 4 {
            continue;
        }
        if handshake[0] != 1 {
            return Parsed::Unknown;
        }
        let total = ((handshake[1] as usize) << 16)
            | ((handshake[2] as usize) << 8)
            | handshake[3] as usize;
        if total > 4092 {
            return Parsed::Unknown;
        }
        if handshake.len() < total + 4 {
            continue;
        }
        return client_hello(&handshake[4..4 + total]).unwrap_or(Parsed::Unknown);
    }
    Parsed::NeedMore
}
fn client_hello(bytes: &[u8]) -> Option<Parsed> {
    let mut at = 34;
    let session_len = *bytes.get(at)? as usize;
    at += 1 + session_len;
    let ciphers = u16_at(bytes, at)?;
    at += 2 + ciphers;
    let compression = *bytes.get(at)? as usize;
    at += 1 + compression;
    let extensions = u16_at(bytes, at)?;
    at += 2;
    let end = at.checked_add(extensions)?;
    if end > bytes.len() {
        return None;
    }
    while at + 4 <= end {
        let kind = u16_at(bytes, at)?;
        let len = u16_at(bytes, at + 2)?;
        at += 4;
        if at + len > end {
            return None;
        }
        if kind == 0 {
            let data = &bytes[at..at + len];
            let total = u16_at(data, 0)?;
            if total + 2 != data.len() {
                return None;
            }
            let mut item = 2;
            while item + 3 <= data.len() {
                let kind = data[item];
                let count = u16_at(data, item + 1)?;
                item += 3;
                let name = data.get(item..item + count)?;
                if kind == 0 {
                    return host(std::str::from_utf8(name).ok()?).map(Parsed::Tls);
                }
                item += count;
            }
        }
        at += len;
    }
    None
}
pub async fn read_initial<S: AsyncRead + Unpin>(client: &mut S) -> (Vec<u8>, Parsed) {
    let mut bytes = Vec::with_capacity(4096);
    let deadline = tokio::time::Instant::now() + Duration::from_millis(750);
    loop {
        let parsed = parse(&bytes);
        if parsed != Parsed::NeedMore || bytes.len() == 4096 {
            return (bytes, parsed);
        }
        let mut buffer = [0; 4096];
        let available = 4096 - bytes.len();
        match tokio::time::timeout_at(deadline, client.read(&mut buffer[..available])).await {
            Ok(Ok(0)) | Ok(Err(_)) | Err(_) => return (bytes, Parsed::Unknown),
            Ok(Ok(len)) => bytes.extend_from_slice(&buffer[..len]),
        }
    }
}
