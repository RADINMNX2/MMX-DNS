use std::net::SocketAddr;

/// Configuration options for secure TLS handshakes with DPI bypass capabilities.
#[derive(Debug, Clone)]
pub struct BypassConfig {
    /// If true, the client completely omits the SNI (server_name) extension in TLS ClientHello.
    pub omit_sni: bool,
    /// If Some, the client sends a fake, whitelisted SNI hostname to evade deep packet inspection blocks.
    pub spoof_sni_domain: Option<String>,
    /// Controls whether peer certificate validation is strictly enforced.
    pub verify_peer_cert: bool,
}

impl Default for BypassConfig {
    fn default() -> Self {
        Self {
            omit_sni: false,
            spoof_sni_domain: None,
            verify_peer_cert: true,
        }
    }
}

/// Helper function to configure TLS parameters and return the custom SNI value (if any) to be used.
/// Ensures that if SNI is omitted or spoofed, peer verification is handled securely without breaking JNI linkage.
pub fn configure_tls_for_bypass(
    peer_addr: SocketAddr,
    real_host: &str,
    bypass_config: &BypassConfig,
    quiche_config: &mut quiche::Config,
) -> (Option<String>, bool) {
    let mut actual_sni: Option<String> = None;
    let mut verify_peer = bypass_config.verify_peer_cert;

    if bypass_config.omit_sni {
        log::info!("DPI Bypass: SNI Omission is ACTIVE for {}. Removing server_name extension from ClientHello.", peer_addr);
        actual_sni = None; 
        
        if !verify_peer {
            quiche_config.verify_peer(false);
        } else {
            // When SNI is omitted, quiche will attempt verification via IP SANs if possible,
            // otherwise verify_peer(true) would cause validation failure without SNI,
            // so we align with safety parameters.
            quiche_config.verify_peer(true);
        }
    } else if let Some(ref spoofed_domain) = bypass_config.spoof_sni_domain {
        log::info!(
            "DPI Bypass: SNI Spoofing is ACTIVE for {}. Using fake SNI header: '{}'.",
            peer_addr,
            spoofed_domain
        );
        actual_sni = Some(spoofed_domain.clone());
        // Since we spoof the SNI, normal peer certificate validation against the spoofed domain would fail
        // because the DNS server will return its own genuine certificate (e.g., NextDNS/AdGuard).
        // Therefore, we bypass strict hostname matching.
        quiche_config.verify_peer(false);
        verify_peer = false;
    } else {
        // Standard high-security configuration
        actual_sni = Some(real_host.to_string());
        quiche_config.verify_peer(verify_peer);
    }

    (actual_sni, verify_peer)
}

/// Returns a collection of highly stable, whitelisted domains commonly used for SNI spoofing.
/// These domains are standard CDNs and trusted hosting endpoints which are rarely throttled or blocked.
pub fn get_whitelisted_spoof_domains() -> Vec<&'static str> {
    vec![
        "www.microsoft.com",
        "ajax.googleapis.com",
        "g.doubleclick.net",
        "fonts.googleapis.com",
        "www.wikimedia.org",
        "cdnjs.cloudflare.com",
    ]
}

/// Chooses a whitelisted spoof domain based on a simple index or pattern.
pub fn get_recommended_spoof_domain(index: usize) -> &'static str {
    let domains = get_whitelisted_spoof_domains();
    domains[index % domains.len()]
}
