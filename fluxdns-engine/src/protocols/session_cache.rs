use std::collections::HashMap;
use std::sync::RwLock;
use once_cell::sync::Lazy;

/// A thread-safe, optimized in-memory cache for persisting QUIC TLS session tickets
/// to enable lightning-fast 0-RTT session resumptions.
pub struct SessionCache {
    // Maps a remote DNS server address (e.g. "8.8.8.8:853") to its TLS session ticket bytes
    cache: RwLock<HashMap<String, Vec<u8>>>,
}

impl SessionCache {
    pub fn new() -> Self {
        SessionCache {
            cache: RwLock::new(HashMap::new()),
        }
    }

    /// Stores a serialized TLS session ticket for a given server address.
    pub fn insert(&self, server: &str, ticket: Vec<u8>) {
        if let Ok(mut writer) = self.cache.write() {
            log::info!("Persisting new 0-RTT TLS session ticket for server: {}", server);
            writer.insert(server.to_string(), ticket);
        } else {
            log::error!("SessionCache: Failed to acquire write lock to insert ticket.");
        }
    }

    /// Retrieves a cached TLS session ticket for a given server address, if available.
    pub fn get(&self, server: &str) -> Option<Vec<u8>> {
        if let Ok(reader) = self.cache.read() {
            let ticket = reader.get(server).cloned();
            if ticket.is_some() {
                log::info!("Cache Hit: Retreived 0-RTT TLS session ticket for server: {}", server);
            } else {
                log::info!("Cache Miss: No 0-RTT TLS session ticket found for server: {}", server);
            }
            ticket
        } else {
            log::error!("SessionCache: Failed to acquire read lock to retrieve ticket.");
            None
        }
    }

    /// Evicts a cached ticket (e.g., if it is expired or rejected by the server).
    pub fn remove(&self, server: &str) {
        if let Ok(mut writer) = self.cache.write() {
            log::info!("Evicting session ticket for server: {}", server);
            writer.remove(server);
        } else {
            log::error!("SessionCache: Failed to acquire write lock to remove ticket.");
        }
    }

    /// Clears all cached session tickets.
    pub fn clear(&self) {
        if let Ok(mut writer) = self.cache.write() {
            log::info!("Clearing all cached 0-RTT TLS session tickets.");
            writer.clear();
        } else {
            log::error!("SessionCache: Failed to acquire write lock to clear cache.");
        }
    }
}

/// Global, thread-safe static instance of our 0-RTT Session Cache.
pub static GLOBAL_SESSION_CACHE: Lazy<SessionCache> = Lazy::new(SessionCache::new);
