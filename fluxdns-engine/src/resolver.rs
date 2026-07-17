/// Asynchronous DNS Query Resolution Engine supporting UDP, DoH, DoT, DoQ, and DoH3 with automatic fallback cascade.

use std::time::Duration;
use tokio::net::UdpSocket;

pub async fn resolve_query(
    query: &[u8],
    primary_dns: &str,
    secondary_dns: &str,
    protocol: &str,
) -> Option<Vec<u8>> {
    let servers = if secondary_dns.is_empty() || secondary_dns == primary_dns {
        vec![primary_dns]
    } else {
        vec![primary_dns, secondary_dns]
    };

    // Attempt resolution across servers in order of priority
    for server in &servers {
        match protocol {
            "DoQ" => {
                // 1. Try standard DNS-over-QUIC (DoQ) on Port 784 first
                match crate::protocols::doq::resolve_via_doq(query, server, 784).await {
                    Ok(res) => return Some(res),
                    Err(e) => {
                        log::warn!("DoQ resolution failed on Port 784: {}. Retrying on Port 853...", e);
                        
                        // 2. Try DoQ on Port 853 if Port 784 is blocked/unresponsive
                        match crate::protocols::doq::resolve_via_doq(query, server, 853).await {
                            Ok(res) => return Some(res),
                            Err(e2) => {
                                log::warn!("DoQ resolution failed on Port 853: {}. ISP throttling suspected. Falling back to HTTP/3 (DoH3) over Port 443...", e2);
                                
                                // 3. Auto fallback to backup DoH3 on Port 443
                                match crate::protocols::doh3::resolve_via_doh3(query, server, 443).await {
                                    Ok(res) => {
                                        log::info!("Fallback DoH3 resolution succeeded on Port 443.");
                                        return Some(res);
                                    }
                                    Err(e3) => {
                                        log::error!("DoH3 fallback resolution also failed for server {}: {}", server, e3);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "DoH3" => {
                // Direct DNS-over-HTTP/3 (DoH3) over Port 443
                match crate::protocols::doh3::resolve_via_doh3(query, server, 443).await {
                    Ok(res) => return Some(res),
                    Err(e) => {
                        log::warn!("DoH3 resolution failed for {}: {}", server, e);
                    }
                }
            }
            "DoH" => {
                let doh_url = if *server == "8.8.8.8" || *server == "8.8.4.4" {
                    "https://dns.google/dns-query"
                } else if *server == "1.1.1.1" || *server == "1.0.0.1" {
                    "https://cloudflare-dns.com/dns-query"
                } else if *server == "9.9.9.9" {
                    "https://dns.quad9.net/dns-query"
                } else {
                    &format!("https://{}/dns-query", server)
                };

                match resolve_via_doh(query, server, doh_url).await {
                    Ok(res) => return Some(res),
                    Err(e) => {
                        log::warn!("DoH resolution failed for {} on {}: {:?}", server, doh_url, e);
                    }
                }
            }
            "DoT" => {
                match resolve_via_dot(query, server).await {
                    Ok(res) => return Some(res),
                    Err(e) => {
                        log::warn!("DoT resolution failed for {}: {:?}", server, e);
                    }
                }
            }
            _ => {
                // Standard low-latency UDP
                match resolve_via_udp(query, server).await {
                    Ok(res) => return Some(res),
                    Err(e) => {
                        log::warn!("UDP resolution failed for {}: {:?}", server, e);
                    }
                }
            }
        }
    }

    // Dynamic fail-safe: automatically fall back to optimized UDP if secure protocols failed or timed out
    if protocol == "DoH" || protocol == "DoT" || protocol == "DoQ" || protocol == "DoH3" {
        for server in &servers {
            if server.is_empty() { continue; }
            match resolve_via_udp(query, server).await {
                Ok(res) => {
                    log::info!("Fallback UDP resolution succeeded for server {}", server);
                    return Some(res);
                }
                Err(e) => {
                    log::warn!("Fallback UDP resolution failed for {}: {:?}", server, e);
                }
            }
        }
    }

    None
}

/// Resolves standard DNS query over UDP Port 53.
pub async fn resolve_via_udp(query: &[u8], dns_ip: &str) -> Result<Vec<u8>, std::io::Error> {
    let socket = UdpSocket::bind("0.0.0.0:0").await?;
    let target = format!("{}:53", dns_ip);
    
    socket.connect(&target).await?;
    socket.send(query).await?;
    
    let mut buffer = [0u8; 4096];
    let (len, _) = tokio::time::timeout(
        Duration::from_millis(1500),
        socket.recv_from(&mut buffer)
    ).await??;
    
    Ok(buffer[..len].to_vec())
}

/// Resolves DNS query over HTTPS (DoH) via POST request.
pub async fn resolve_via_doh(
    query: &[u8],
    _dns_ip: &str,
    doh_url: &str,
) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(3000))
        .build()?;
    
    let response = client.post(doh_url)
        .header("content-type", "application/dns-message")
        .header("accept", "application/dns-message")
        .body(query.to_vec())
        .send()
        .await?;
        
    if response.status().is_success() {
        let bytes = response.bytes().await?;
        Ok(bytes.to_vec())
    } else {
        Err(format!("DoH query failed with status code: {}", response.status()).into())
    }
}

/// Resolves DNS query over TLS (DoT) via Port 853 with TCP length-prefixed stream.
pub async fn resolve_via_dot(
    query: &[u8],
    dns_ip: &str,
) -> Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpStream;
    use std::sync::Arc;

    let target = format!("{}:853", dns_ip);
    let socket = TcpStream::connect(&target).await?;

    // Create root cert store using webpki certificates
    let mut root_store = rustls::RootCertStore::empty();
    root_store.add_trust_anchors(webpki_roots::TLS_SERVER_ROOTS.iter().map(|ta| {
        rustls::OwnedTrustAnchor::from_subject_spki_name_constraints(
            ta.subject,
            ta.spki,
            ta.name_constraints,
        )
    }));

    let config = rustls::ClientConfig::builder()
        .with_safe_defaults()
        .with_root_certificates(root_store)
        .with_no_client_auth();

    let connector = tokio_rustls::TlsConnector::from(Arc::new(config));
    let domain = rustls::ServerName::try_from(dns_ip)
        .or_else(|_| rustls::ServerName::try_from("dns.google"))?; // Fallback domain for IP-only hosts

    let mut tls_stream = connector.connect(domain, socket).await?;

    // RFC 7858: Write 2-byte length header prefix + payload
    let len = query.len();
    let header = [(len >> 8) as u8, len as u8];
    tls_stream.write_all(&header).await?;
    tls_stream.write_all(query).await?;
    tls_stream.flush().await?;

    // Read response 2-byte length header
    let mut len_buf = [0u8; 2];
    tls_stream.read_exact(&mut len_buf).await?;
    let response_len = ((len_buf[0] as usize) << 8) | (len_buf[1] as usize);

    // Read response payload
    let mut response_buf = vec![0u8; response_len];
    tls_stream.read_exact(&mut response_buf).await?;

    Ok(response_buf)
}
