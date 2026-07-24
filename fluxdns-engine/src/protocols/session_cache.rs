use dashmap::DashMap;
use once_cell::sync::Lazy;

/// A lock-free, concurrent cache for persisting QUIC TLS session tickets
/// to enable lightning-fast 0-RTT session resumptions across worker threads.
pub struct SessionCache {
    // Maps a remote DNS server address (e.g. "8.8.8.8:853") to its TLS session ticket bytes
    cache: DashMap<String, Vec<u8>>,
}

impl SessionCache {
    pub fn new() -> Self {
        SessionCache {
            cache: DashMap::new(),
        }
    }

    /// Stores a serialized TLS session ticket for a given server address.
    pub fn insert(&self, server: &str, ticket: Vec<u8>) {
        log::info!("Persisting new 0-RTT TLS session ticket for server: {}", server);
        self.cache.insert(server.to_string(), ticket);
    }

    /// Retrieves a cached TLS session ticket for a given server address, if available.
    pub fn get(&self, server: &str) -> Option<Vec<u8>> {
        if let Some(ticket_ref) = self.cache.get(server) {
            log::info!("Cache Hit: Retreived 0-RTT TLS session ticket for server: {}", server);
            Some(ticket_ref.value().clone())
        } else {
            log::info!("Cache Miss: No 0-RTT TLS session ticket found for server: {}", server);
            None
        }
    }

    /// Evicts a cached ticket (e.g., if it is expired or rejected by the server).
    pub fn remove(&self, server: &str) {
        log::info!("Evicting session ticket for server: {}", server);
        self.cache.remove(server);
    }

    /// Clears all cached session tickets.
    pub fn clear(&self) {
        log::info!("Clearing all cached 0-RTT TLS session tickets.");
        self.cache.clear();
    }
}

/// Global, thread-safe static instance of our 0-RTT Session Cache.
pub static GLOBAL_SESSION_CACHE: Lazy<SessionCache> = Lazy::new(SessionCache::new);
