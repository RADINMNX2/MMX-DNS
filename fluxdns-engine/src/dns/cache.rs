use std::time::{Duration, Instant};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;
use dashmap::DashMap;
use once_cell::sync::Lazy;

/// Represents a single cached DNS resolution transaction.
pub struct CacheEntry {
    /// The original DNS response payload.
    pub response: Vec<u8>,
    /// Instant when this entry is considered stale and must be evicted or refreshed.
    pub expires_at: Instant,
    /// Parsed hostname associated with this query.
    pub domain: String,
    /// Parsed DNS Query Type (e.g. A, AAAA).
    pub qtype: u16,
    /// The neutralized raw DNS query payload (with transaction ID zeroed) used to trigger prefetching.
    pub query_key: Vec<u8>,
    /// Thread-safe count of hits on this cache record to prioritize hot prefetching.
    pub hits: AtomicU32,
}

/// A highly concurrent, lock-free key-value DNS cache with reactive prefetching.
pub struct DnsCache {
    /// Lock-free, thread-safe concurrent map.
    /// Key: Vector of raw query bytes with transaction ID neutralized (bytes 0 and 1 set to 0).
    map: DashMap<Vec<u8>, Arc<CacheEntry>>,
}

impl DnsCache {
    pub fn new() -> Self {
        DnsCache {
            map: DashMap::new(),
        }
    }

    /// Queries the lock-free cache. If a match is found:
    /// 1. Increments hits.
    /// 2. Restores the caller's unique 2-byte Transaction ID in the response.
    /// 3. Returns the populated response in 0ms.
    pub fn get(&self, query: &[u8]) -> Option<Vec<u8>> {
        if query.len() < 12 {
            return None;
        }

        let key = neutralize_transaction_id(query);
        if let Some(entry) = self.map.get(&key) {
            let entry_val = entry.value();
            if Instant::now() > entry_val.expires_at {
                return None; // Entry expired, janitor will evict soon
            }

            entry_val.hits.fetch_add(1, Ordering::SeqCst);

            // Re-apply original Transaction ID to the cached response
            let mut response = entry_val.response.clone();
            if response.len() >= 2 {
                response[0] = query[0];
                response[1] = query[1];
            }
            return Some(response);
        }
        None
    }

    /// Caches a newly resolved DNS response.
    /// Dynamically parses the minimum TTL from the response answers to set accurate expiry.
    pub fn insert(&self, query: &[u8], response: &[u8]) {
        if query.len() < 12 || response.len() < 12 {
            return;
        }

        let key = neutralize_transaction_id(query);
        let (domain, qtype) = parse_dns_query_info(query).unwrap_or((String::new(), 1));
        
        // Parse TTL from response or default to 60 seconds
        let ttl_secs = parse_dns_response_min_ttl(response).unwrap_or(60);
        let expires_at = Instant::now() + Duration::from_secs(ttl_secs as u64);

        log::info!(
            "DnsCache: Caching resolution for {} (Type {}). TTL parsed: {}s",
            domain, qtype, ttl_secs
        );

        let entry = Arc::new(CacheEntry {
            response: response.to_vec(),
            expires_at,
            domain,
            qtype,
            query_key: key.clone(),
            hits: AtomicU32::new(0),
        });

        self.map.insert(key, entry);
    }

    /// Evicts a specific key from the cache.
    pub fn remove(&self, query: &[u8]) {
        let key = neutralize_transaction_id(query);
        self.map.remove(&key);
    }

    /// Clears the entire cache.
    pub fn clear(&self) {
        self.map.clear();
    }

    /// Starts the background janitor thread that evicts expired entries
    /// and proactively prefetches expiring records that are highly requested.
    pub fn start_janitor_and_prefetcher(
        self_arc: Arc<Self>,
        primary_dns: String,
        secondary_dns: String,
        protocol: String,
    ) {
        tokio::spawn(async move {
            log::info!("DnsCache: Background janitor and prefetcher thread active.");
            loop {
                tokio::time::sleep(Duration::from_secs(5)).await;
                
                let now = Instant::now();
                let mut keys_to_remove = Vec::new();
                let mut records_to_prefetch = Vec::new();

                for item in self_arc.map.iter() {
                    let key = item.key();
                    let entry = item.value();

                    if now > entry.expires_at {
                        keys_to_remove.push(key.clone());
                    } else if entry.expires_at.duration_since(now) < Duration::from_secs(10) {
                        // Expiring in less than 10 seconds.
                        // If it has hits (popular), prefetch it!
                        if entry.hits.load(Ordering::SeqCst) > 0 {
                            records_to_prefetch.push((
                                key.clone(),
                                entry.domain.clone(),
                                entry.qtype,
                                entry.query_key.clone(),
                            ));
                        }
                    }
                }

                // 1. Evict expired entries
                for key in keys_to_remove {
                    self_arc.map.remove(&key);
                    log::info!("DnsCache Janitor: Evicted expired DNS cache record.");
                }

                // 2. Proactively prefetch expiring active entries
                for (key, domain, qtype, query_payload) in records_to_prefetch {
                    let primary = primary_dns.clone();
                    let secondary = secondary_dns.clone();
                    let proto = protocol.clone();
                    let self_clone = Arc::clone(&self_arc);

                    log::info!(
                        "DnsCache Janitor: Proactively prefetching expiring domain: {} (Type {})",
                        domain, qtype
                    );

                    tokio::spawn(async move {
                        // Build a real dummy Transaction ID (we will use 0x1234)
                        let mut request = query_payload.clone();
                        if request.len() >= 2 {
                            request[0] = 0x12;
                            request[1] = 0x34;
                        }

                        // Resolve via the asynchronous resolver
                        if let Some(reply) = crate::resolver::resolve_query(
                            &request,
                            &primary,
                            &secondary,
                            &proto,
                        ).await {
                            log::info!("DnsCache Janitor: Prefetch successful for {}.", domain);
                            self_clone.insert(&request, &reply);
                        } else {
                            log::warn!("DnsCache Janitor: Prefetch failed for {}.", domain);
                        }
                    });
                }
            }
        });
    }
}

/// Global static lazy instance of the Lock-Free DNS Cache.
pub static GLOBAL_DNS_CACHE: Lazy<Arc<DnsCache>> = Lazy::new(|| Arc::new(DnsCache::new()));

/// Returns a copy of the query with bytes 0 and 1 (Transaction ID) zeroed out.
fn neutralize_transaction_id(query: &[u8]) -> Vec<u8> {
    let mut key = query.to_vec();
    if key.len() >= 2 {
        key[0] = 0;
        key[1] = 0;
    }
    key
}

/// Helper to parse DNS Question domain name and query type.
fn parse_dns_query_info(query: &[u8]) -> Option<(String, u16)> {
    if query.len() < 12 {
        return None;
    }
    let (domain, offset) = parse_dns_name(query, 12)?;
    if offset + 4 <= query.len() {
        let qtype = u16::from_be_bytes([query[offset], query[offset + 1]]);
        Some((domain, qtype))
    } else {
        Some((domain, 1))
    }
}

/// Helper to parse the minimum TTL among the answer records in a response.
fn parse_dns_response_min_ttl(response: &[u8]) -> Option<u32> {
    if response.len() < 12 {
        return None;
    }

    let qd_count = u16::from_be_bytes([response[4], response[5]]) as usize;
    let an_count = u16::from_be_bytes([response[6], response[7]]) as usize;

    let mut offset = 12;

    // Skip all question records
    for _ in 0..qd_count {
        let (_, next_offset) = parse_dns_name(response, offset)?;
        offset = next_offset + 4; // Skip QType and QClass
    }

    let mut min_ttl = u32::MAX;

    // Parse answer records
    for _ in 0..an_count {
        let (_, next_offset) = parse_dns_name(response, offset)?;
        offset = next_offset;

        if offset + 10 > response.len() {
            break;
        }

        let _rtype = u16::from_be_bytes([response[offset], response[offset + 1]]);
        let _rclass = u16::from_be_bytes([response[offset + 2], response[offset + 3]]);
        let ttl = u32::from_be_bytes([
            response[offset + 4],
            response[offset + 5],
            response[offset + 6],
            response[offset + 7],
        ]);
        let rd_len = u16::from_be_bytes([response[offset + 8], response[offset + 9]]) as usize;

        if ttl < min_ttl && ttl > 0 {
            min_ttl = ttl;
        }

        offset += 10 + rd_len;
    }

    if min_ttl == u32::MAX {
        None
    } else {
        Some(min_ttl)
    }
}

/// Robust DNS Label decoder
fn parse_dns_name(data: &[u8], mut offset: usize) -> Option<(String, usize)> {
    let mut name = String::new();
    let mut jumped = false;
    let mut jump_offset = 0;
    let mut visited = 0;
    let len = data.len();

    loop {
        if offset >= len {
            return None;
        }
        let count = data[offset] as usize;
        if (count & 0xC0) == 0xC0 {
            if offset + 1 >= len {
                return None;
            }
            if !jumped {
                jump_offset = offset + 2;
                jumped = true;
            }
            let pointer = (((count & 0x3F) << 8) | (data[offset + 1] as usize)) as usize;
            offset = pointer;
            visited += 2;
            if visited > len {
                return None;
            }
            continue;
        }

        offset += 1;
        if count == 0 {
            break;
        }

        if offset + count > len {
            return None;
        }

        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(&String::from_utf8_lossy(&data[offset..offset + count]));
        offset += count;
    }

    let final_offset = if jumped { jump_offset } else { offset };
    Some((name, final_offset))
}
