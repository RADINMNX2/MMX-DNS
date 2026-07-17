use std::time::Duration;
use std::sync::Arc;
use crate::dns::cache::GLOBAL_DNS_CACHE;
use crate::dns::racer::race_query;

/// Predefined list of popular e-sports, matchmaking, and multiplayer gaming endpoints
/// to maintain at absolute zero-latency (0ms) in the local DNS cache.
const GAMING_ENDPOINTS: &[&str] = &[
    // Epic Games / Fortnite Matchmaking
    "matchmaking.epicgames.com",
    "fortnite-public-service-prod11.ol.epicgames.com",
    "lightswitch-public-service-prod06.ol.epicgames.com",
    
    // Riot Games / Valorant / League of Legends Matchmaking (Riot Bahrain, Frankfurt, NA)
    "br.chat.si.riotgames.com",
    "la1.chat.si.riotgames.com",
    "na2.chat.si.riotgames.com",
    "euw1.chat.si.riotgames.com",
    "eune1.chat.si.riotgames.com",
    "ap.chat.si.riotgames.com",
    "riot-matchmaking-prod.com",
    
    // Activision / Call of Duty: Mobile
    "matchmaking.codmobile.com",
    "auth.codmobile.com",
    "game-server.codmobile.com",
    
    // Steam / Valve CS:GO & matchmaking
    "cm1-fra1.steamgames.com",
    "cm1-lux1.steamgames.com",
    "matchmaking-service.valve.com",
    
    // PUBG Mobile Matchmaking
    "pubgmobile.com",
    "na-matchmaking.pubgmobile.com",
    "eu-matchmaking.pubgmobile.com",
    
    // Mobile Legends: Bang Bang
    "mobilelegends.com",
    "matchmaker.mobilelegends.com",
    
    // Roblox Matchmaking
    "matchmaker.roblox.com",
    "assetgame.roblox.com",
    
    // Minecraft Session Services
    "session.minecraft.net",
    "multiplayer.minecraft.net"
];

/// Builds a standards-compliant Type-A DNS Query payload for a given domain name.
pub fn build_dns_query(domain: &str) -> Vec<u8> {
    let mut query = Vec::with_capacity(domain.len() + 18);
    
    // Transaction ID: 0x1234
    query.extend_from_slice(&[0x12, 0x34]);
    // Flags: Standard query with Recursion Desired (0x0100)
    query.extend_from_slice(&[0x01, 0x00]);
    // Questions: 1
    query.extend_from_slice(&[0x00, 0x01]);
    // Answers: 0, Authority: 0, Additional: 0
    query.extend_from_slice(&[0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);

    // Encode Domain Labels
    for label in domain.split('.') {
        if !label.is_empty() {
            query.push(label.len() as u8);
            query.extend_from_slice(label.as_bytes());
        }
    }
    // Zero terminator
    query.push(0x00);

    // QType: A (0x0001)
    query.extend_from_slice(&[0x00, 0x01]);
    // QClass: IN (0x0001)
    query.extend_from_slice(&[0x00, 0x01]);

    query
}

/// Starts the Predictive DNS Pre-fetching Daemon background worker.
pub fn start_predictive_prefetcher(
    primary_dns: String,
    secondary_dns: String,
    protocol: String,
) {
    tokio::spawn(async move {
        log::info!("Predictive Pre-fetching Daemon: Background worker active.");
        
        // Immediate first run to warm up the cache
        perform_prefetch_cycle(&primary_dns, &secondary_dns, &protocol).await;

        loop {
            // Predictively refresh mapping entries every 2.5 minutes (150 seconds)
            tokio::time::sleep(Duration::from_secs(150)).await;
            perform_prefetch_cycle(&primary_dns, &secondary_dns, &protocol).await;
        }
    });
}

/// Asynchronously resolves all gaming endpoints and populates the local cache.
async fn perform_prefetch_cycle(
    primary_dns: &str,
    secondary_dns: &str,
    protocol: &str,
) {
    log::info!("Predictive Pre-fetching Daemon: Refreshing e-sports and matchmaking endpoints...");
    
    for domain in GAMING_ENDPOINTS {
        let query_payload = build_dns_query(domain);
        
        let primary = primary_dns.to_string();
        let secondary = secondary_dns.to_string();
        let proto = protocol.to_string();
        let domain_str = domain.to_string();

        tokio::spawn(async move {
            // Use the race_query engine to find the fastest responder!
            if let Some(response) = race_query(&query_payload, &primary, &secondary, &proto).await {
                log::info!(
                    "Predictive Pre-fetching Daemon: Successful cache warm-up for gaming endpoint: {}",
                    domain_str
                );
                GLOBAL_DNS_CACHE.insert(&query_payload, &response);
            } else {
                log::warn!(
                    "Predictive Pre-fetching Daemon: Failed to predictively prefetch domain: {}",
                    domain_str
                );
            }
        });
        
        // Minor interval staggering between requests to prevent upstream network congestion spikes
        tokio::time::sleep(Duration::from_millis(150)).await;
    }
    log::info!("Predictive Pre-fetching Daemon: Gaming endpoint pre-fetching cycle complete.");
}
