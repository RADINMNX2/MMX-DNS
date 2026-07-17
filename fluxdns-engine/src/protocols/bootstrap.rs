use std::collections::HashMap;
use once_cell::sync::Lazy;

/// A mapping of secure DNS provider domains or identifiers to stable Anycast Bootstrap IPs.
/// This eliminates the requirement to perform Port 53 plaintext lookups, avoiding DNS bootstrapping latency and censorship.
pub static BOOTSTRAP_DNS_MAP: Lazy<HashMap<&'static str, Vec<&'static str>>> = Lazy::new(|| {
    let mut m = HashMap::new();
    
    // NextDNS Bootstrap configurations (Stable Anycast IPs)
    m.insert("dns.nextdns.io", vec!["45.90.28.0", "45.90.30.0"]);
    m.insert("nextdns", vec!["45.90.28.0", "45.90.30.0"]);
    
    // AdGuard DNS Bootstrap configurations (Stable Anycast IPs)
    m.insert("dns.adguard-dns.com", vec!["94.140.14.14", "94.140.15.15"]);
    m.insert("dns.adguard.com", vec!["94.140.14.14", "94.140.15.15"]);
    m.insert("adguard", vec!["94.140.14.14", "94.140.15.15"]);

    // Google DNS Bootstrap configurations
    m.insert("dns.google", vec!["8.8.8.8", "8.8.4.4"]);
    m.insert("google", vec!["8.8.8.8", "8.8.4.4"]);

    // Cloudflare DNS Bootstrap configurations
    m.insert("cloudflare-dns.com", vec!["1.1.1.1", "1.0.0.1"]);
    m.insert("cloudflare", vec!["1.1.1.1", "1.0.0.1"]);

    // Quad9 DNS Bootstrap configurations
    m.insert("dns.quad9.net", vec!["9.9.9.9", "149.112.112.112"]);
    m.insert("quad9", vec!["9.9.9.9", "149.112.112.112"]);

    m
});

/// Resolves a secure DNS domain or provider identifier directly into a set of stable Anycast bootstrap IPs,
/// completely avoiding the Port 53 bootstrap dependency.
pub fn resolve_bootstrap_ips(provider_or_domain: &str) -> Vec<String> {
    let cleaned = provider_or_domain.trim().to_lowercase();
    
    // Check direct matching in map
    if let Some(ips) = BOOTSTRAP_DNS_MAP.get(cleaned.as_str()) {
        return ips.iter().map(|s| s.to_string()).collect();
    }
    
    // Perform partial matching (e.g., if domain contains "nextdns" or "adguard")
    for (key, ips) in BOOTSTRAP_DNS_MAP.iter() {
        if cleaned.contains(key) || key.contains(&cleaned) {
            return ips.iter().map(|s| s.to_string()).collect();
        }
    }
    
    // Fallback: If it's already an IP address, return it
    if cleaned.parse::<std::net::IpAddr>().is_ok() {
        return vec![cleaned];
    }
    
    // Empty if completely unknown
    vec![]
}
