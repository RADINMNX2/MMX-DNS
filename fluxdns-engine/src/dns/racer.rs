use tokio::sync::mpsc;
use std::time::Duration;

/// Races multiple query resolution requests in parallel and returns the fastest responder.
pub async fn race_query(
    query: &[u8],
    primary_dns: &str,
    secondary_dns: &str,
    protocol: &str,
) -> Option<Vec<u8>> {
    // 0ms Zero-Latency Lookup Check
    if let Some(cached_response) = super::cache::GLOBAL_DNS_CACHE.get(query) {
        log::info!("PDR: Cache Hit! Returning resolved response instantly from RAM (0ms).");
        return Some(cached_response);
    }

    log::info!("PDR: Cache Miss. Initiating highly concurrent Parallel DNS Racing for: {}", protocol);

    // Multi-producer single-consumer channel to accept the fastest successful racer
    let (tx, mut rx) = mpsc::channel::<Vec<u8>>(10);
    let mut handles = Vec::new();

    // Compile the racing target servers
    let mut targets = Vec::new();
    if !primary_dns.is_empty() {
        targets.push((primary_dns.to_string(), protocol.to_string()));
    }
    if !secondary_dns.is_empty() && secondary_dns != primary_dns {
        targets.push((secondary_dns.to_string(), protocol.to_string()));
    }

    // Ingress robust Anycast fallback servers to race against slow primary ISP routes
    let anycast_dns = if protocol == "DoQ" || protocol == "DoH3" {
        "1.1.1.1".to_string() // Cloudflare Anycast Secure
    } else {
        "8.8.8.8".to_string() // Google Anycast Standard
    };
    targets.push((anycast_dns, protocol.to_string()));

    // Dispatch the parallel resolution tasks
    for (dns_ip, proto) in targets {
        let query_bytes = query.to_vec();
        let tx_clone = tx.clone();

        let handle = tokio::spawn(async move {
            match proto.as_str() {
                "DoQ" => {
                    // Try DoQ on Port 784, then Port 853
                    if let Ok(res) = crate::protocols::doq::resolve_via_doq(&query_bytes, &dns_ip, 784).await {
                        let _ = tx_clone.send(res).await;
                    } else if let Ok(res) = crate::protocols::doq::resolve_via_doq(&query_bytes, &dns_ip, 853).await {
                        let _ = tx_clone.send(res).await;
                    }
                }
                "DoH3" => {
                    // HTTP/3 on Port 443
                    if let Ok(res) = crate::protocols::doh3::resolve_via_doh3(&query_bytes, &dns_ip, 443).await {
                        let _ = tx_clone.send(res).await;
                    }
                }
                "DoH" => {
                    // DoH URL routing
                    let doh_url = if dns_ip == "8.8.8.8" || dns_ip == "8.8.4.4" {
                        "https://dns.google/dns-query".to_string()
                    } else if dns_ip == "1.1.1.1" || dns_ip == "1.0.0.1" {
                        "https://cloudflare-dns.com/dns-query".to_string()
                    } else {
                        format!("https://{}/dns-query", dns_ip)
                    };
                    if let Ok(res) = crate::resolver::resolve_via_doh(&query_bytes, &dns_ip, &doh_url).await {
                        let _ = tx_clone.send(res).await;
                    }
                }
                "DoT" => {
                    // TLS on Port 853
                    if let Ok(res) = crate::resolver::resolve_via_dot(&query_bytes, &dns_ip).await {
                        let _ = tx_clone.send(res).await;
                    }
                }
                _ => {
                    // Fallback to high speed UDP
                    if let Ok(res) = crate::resolver::resolve_via_udp(&query_bytes, &dns_ip).await {
                        let _ = tx_clone.send(res).await;
                    }
                }
            }
        });
        handles.push(handle);
    }

    // Always inject an ultra-low-latency UDP runner as a secure speed racer fallback!
    if protocol != "UDP" {
        let query_bytes = query.to_vec();
        let tx_clone = tx.clone();
        let primary_dns_str = primary_dns.to_string();
        
        let udp_fallback_handle = tokio::spawn(async move {
            if let Ok(res) = crate::resolver::resolve_via_udp(&query_bytes, &primary_dns_str).await {
                let _ = tx_clone.send(res).await;
            }
        });
        handles.push(udp_fallback_handle);
    }

    // Wait for the winning packet, with a global timeout limit of 3.5 seconds
    let winning_packet: Option<Vec<u8>> = tokio::select! {
        winner = rx.recv() => winner,
        _ = tokio::time::sleep(Duration::from_millis(3500)) => {
            log::warn!("PDR: Global racing session timed out before any resolver succeeded.");
            None
        }
    };

    // MICROSECOND WINNER SELECTOR:
    // Abort and discard all pending/slow resolution tasks instantly to save resources
    for handle in handles {
        handle.abort();
    }

    if let Some(ref packet) = winning_packet {
        log::info!("PDR: Racing winner selected. Dispatched to writer queue and caching.");
        super::cache::GLOBAL_DNS_CACHE.insert(query, packet);
    }

    winning_packet
}
