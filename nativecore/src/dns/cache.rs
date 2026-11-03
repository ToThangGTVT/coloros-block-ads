//! Bounded positive/negative DNS cache. TTLs are decreased on every hit, never extended.
use hickory_resolver::proto::{
    op::{Message, ResponseCode},
    rr::RData,
};
use std::{collections::HashMap, time::Instant};
struct Cached {
    response: Message,
    inserted: Instant,
    ttl: u32,
    stamp: u64,
    bytes: usize,
}
#[derive(Default)]
pub struct Cache {
    values: HashMap<Vec<u8>, Cached>,
    bytes: usize,
    clock: u64,
}
impl Cache {
    pub fn clear(&mut self) {
        self.values.clear();
        self.bytes = 0;
    }
    pub fn get(&mut self, key: &[u8]) -> Option<Message> {
        self.clock += 1;
        let cached = self.values.get_mut(key)?;
        let elapsed = cached.inserted.elapsed().as_secs();
        if elapsed >= cached.ttl as u64 {
            let expired = self.values.remove(key)?;
            self.bytes -= expired.bytes;
            return None;
        }
        cached.stamp = self.clock;
        let mut message = cached.response.clone();
        for record in message
            .answers
            .iter_mut()
            .chain(message.authorities.iter_mut())
            .chain(message.additionals.iter_mut())
        {
            record.decrement_ttl(elapsed as u32);
        }
        Some(message)
    }
    pub fn put(&mut self, key: Vec<u8>, response: Message) {
        if response.signature.is_some() || response.metadata.truncation {
            return;
        }
        if !matches!(
            response.metadata.response_code,
            ResponseCode::NoError | ResponseCode::NXDomain
        ) {
            return;
        }
        let ttl = if response.answers.is_empty() {
            response
                .authorities
                .iter()
                .filter_map(|record| {
                    if let RData::SOA(soa) = &record.data {
                        Some(record.ttl.min(soa.minimum))
                    } else {
                        None
                    }
                })
                .min()
        } else {
            response.answers.iter().map(|r| r.ttl).min()
        }
        .unwrap_or(0);
        if ttl == 0 {
            return;
        }
        let bytes = key.len() + response.to_vec().map_or(0, |b| b.len());
        if bytes > 65536 {
            return;
        }
        if let Some(old) = self.values.remove(&key) {
            self.bytes -= old.bytes;
        }
        while self.values.len() >= 4096 || self.bytes + bytes > 8 * 1024 * 1024 {
            let Some(oldest) = self
                .values
                .iter()
                .min_by_key(|(_, v)| v.stamp)
                .map(|(k, _)| k.clone())
            else {
                break;
            };
            if let Some(old) = self.values.remove(&oldest) {
                self.bytes -= old.bytes;
            }
        }
        self.clock += 1;
        self.bytes += bytes;
        self.values.insert(
            key,
            Cached {
                response,
                inserted: Instant::now(),
                ttl,
                stamp: self.clock,
                bytes,
            },
        );
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use hickory_resolver::proto::{
        op::OpCode,
        rr::{rdata::A, Name, Record},
    };
    #[test]
    fn zero_ttl_is_not_cached_and_expired_records_are_removed() {
        let mut cache = Cache::default();
        let mut response = Message::response(1, OpCode::Query);
        response.answers.push(Record::from_rdata(
            Name::from_ascii("ttl.test.").unwrap(),
            0,
            RData::A(A::new(1, 2, 3, 4)),
        ));
        cache.put(vec![1], response.clone());
        assert!(cache.get(&[1]).is_none());
        response.answers[0].ttl = 30;
        cache.put(vec![1], response);
        cache.values.get_mut(&vec![1]).unwrap().inserted =
            Instant::now() - std::time::Duration::from_secs(4);
        assert_eq!(cache.get(&[1]).unwrap().answers[0].ttl, 26);
        cache.values.get_mut(&vec![1]).unwrap().inserted =
            Instant::now() - std::time::Duration::from_secs(31);
        assert!(cache.get(&[1]).is_none());
        assert_eq!(cache.bytes, 0);
    }
}
