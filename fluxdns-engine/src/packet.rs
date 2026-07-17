/// Zero-Copy IP/UDP packet parser and builder module for FluxDNS.

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IpVersion {
    IPv4,
    IPv6,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum IpProtocol {
    Udp,
    Tcp,
    Other(u8),
}

#[derive(Debug, Clone)]
pub struct ParsedPacket<'a> {
    pub version: IpVersion,
    pub src_ip: &'a [u8],
    pub dst_ip: &'a [u8],
    pub protocol: IpProtocol,
    pub src_port: u16,
    pub dst_port: u16,
    pub payload: &'a [u8],
}

/// Parses a raw packet read from the TUN interface in a zero-copy manner.
pub fn parse_packet(data: &[u8]) -> Option<ParsedPacket> {
    if data.is_empty() {
        return None;
    }
    
    let version = (data[0] >> 4) & 0x0F;
    if version == 4 {
        if data.len() < 20 {
            return None;
        }
        let ihl = (data[0] & 0x0F) as usize;
        let ip_header_len = ihl * 4;
        if data.len() < ip_header_len {
            return None;
        }
        
        let protocol_num = data[9];
        let src_ip = &data[12..16];
        let dst_ip = &data[16..20];
        
        let protocol = match protocol_num {
            17 => IpProtocol::Udp,
            6 => IpProtocol::Tcp,
            other => IpProtocol::Other(other),
        };
        
        match protocol {
            IpProtocol::Udp => {
                if data.len() < ip_header_len + 8 {
                    return None;
                }
                let src_port = u16::from_be_bytes([data[ip_header_len], data[ip_header_len + 1]]);
                let dst_port = u16::from_be_bytes([data[ip_header_len + 2], data[ip_header_len + 3]]);
                let udp_len = u16::from_be_bytes([data[ip_header_len + 4], data[ip_header_len + 5]]) as usize;
                
                if udp_len < 8 || data.len() < ip_header_len + udp_len {
                    return None;
                }
                
                let payload = &data[ip_header_len + 8..ip_header_len + udp_len];
                
                Some(ParsedPacket {
                    version: IpVersion::IPv4,
                    src_ip,
                    dst_ip,
                    protocol,
                    src_port,
                    dst_port,
                    payload,
                })
            }
            IpProtocol::Tcp => {
                if data.len() < ip_header_len + 20 {
                    return None;
                }
                let src_port = u16::from_be_bytes([data[ip_header_len], data[ip_header_len + 1]]);
                let dst_port = u16::from_be_bytes([data[ip_header_len + 2], data[ip_header_len + 3]]);
                
                let data_offset = ((data[ip_header_len + 12] >> 4) & 0x0F) as usize * 4;
                if data.len() < ip_header_len + data_offset {
                    return None;
                }
                
                let payload = &data[ip_header_len + data_offset..];
                
                Some(ParsedPacket {
                    version: IpVersion::IPv4,
                    src_ip,
                    dst_ip,
                    protocol,
                    src_port,
                    dst_port,
                    payload,
                })
            }
            _ => {
                let payload = &data[ip_header_len..];
                Some(ParsedPacket {
                    version: IpVersion::IPv4,
                    src_ip,
                    dst_ip,
                    protocol,
                    src_port: 0,
                    dst_port: 0,
                    payload,
                })
            }
        }
    } else if version == 6 {
        if data.len() < 40 {
            return None;
        }
        
        let payload_len = u16::from_be_bytes([data[4], data[5]]) as usize;
        let next_header = data[6];
        let src_ip = &data[8..24];
        let dst_ip = &data[24..40];
        
        if data.len() < 40 + payload_len {
            return None;
        }
        
        let protocol = match next_header {
            17 => IpProtocol::Udp,
            6 => IpProtocol::Tcp,
            other => IpProtocol::Other(other),
        };
        
        match protocol {
            IpProtocol::Udp => {
                if data.len() < 48 {
                    return None;
                }
                let src_port = u16::from_be_bytes([data[40], data[41]]);
                let dst_port = u16::from_be_bytes([data[42], data[43]]);
                let udp_len = u16::from_be_bytes([data[44], data[45]]) as usize;
                
                if udp_len < 8 || data.len() < 40 + udp_len {
                    return None;
                }
                
                let payload = &data[48..40 + udp_len];
                
                Some(ParsedPacket {
                    version: IpVersion::IPv6,
                    src_ip,
                    dst_ip,
                    protocol,
                    src_port,
                    dst_port,
                    payload,
                })
            }
            IpProtocol::Tcp => {
                if data.len() < 60 {
                    return None;
                }
                let src_port = u16::from_be_bytes([data[40], data[41]]);
                let dst_port = u16::from_be_bytes([data[42], data[43]]);
                
                let data_offset = ((data[52] >> 4) & 0x0F) as usize * 4;
                if data.len() < 40 + data_offset {
                    return None;
                }
                
                let payload = &data[40 + data_offset..];
                
                Some(ParsedPacket {
                    version: IpVersion::IPv6,
                    src_ip,
                    dst_ip,
                    protocol,
                    src_port,
                    dst_port,
                    payload,
                })
            }
            _ => {
                let payload = &data[40..];
                Some(ParsedPacket {
                    version: IpVersion::IPv6,
                    src_ip,
                    dst_ip,
                    protocol,
                    src_port: 0,
                    dst_port: 0,
                    payload,
                })
            }
        }
    } else {
        None
    }
}

/// Dynamic packet assembler that formats an outbound IP/UDP frame with the resolved DNS payload.
pub fn build_reply_packet(
    dns_reply: &[u8],
    src_ip: &[u8], // Original client source IP
    dst_ip: &[u8], // Original server destination IP
    src_port: u16, // Original client source port
    dst_port: u16, // Original server destination port (53)
) -> Option<Vec<u8>> {
    // If original packet is IPv4
    if src_ip.len() == 4 && dst_ip.len() == 4 {
        let ip_header_len = 20;
        let udp_header_len = 8;
        let total_packet_len = ip_header_len + udp_header_len + dns_reply.len();
        
        let mut packet = vec![0u8; total_packet_len];
        
        // --- IP HEADER ---
        packet[0] = 0x45; // Version 4, IHL = 5 (20 bytes)
        packet[1] = 0x00; // Type of service
        packet[2..4].copy_from_slice(&(total_packet_len as u16).to_be_bytes());
        packet[4..6].copy_from_slice(&0u16.to_be_bytes()); // Identification
        packet[6..8].copy_from_slice(&0x4000u16.to_be_bytes()); // Flags: Don't Fragment (DF)
        packet[8] = 64; // TTL
        packet[9] = 17; // Protocol: UDP (17)
        packet[10..12].copy_from_slice(&0u16.to_be_bytes()); // Checksum placeholder
        
        // Swap IPs: Source IP becomes Destination, Destination IP becomes Source
        packet[12..16].copy_from_slice(dst_ip);
        packet[16..20].copy_from_slice(src_ip);
        
        // Calculate and set IP checksum
        let checksum = calculate_ipv4_checksum(&packet[0..20]);
        packet[10..12].copy_from_slice(&checksum.to_be_bytes());
        
        // --- UDP HEADER ---
        let udp_offset = ip_header_len;
        packet[udp_offset..udp_offset + 2].copy_from_slice(&dst_port.to_be_bytes()); // Src Port is original Dst Port (53)
        packet[udp_offset + 2..udp_offset + 4].copy_from_slice(&src_port.to_be_bytes()); // Dst Port is original Src Port
        let udp_len = (udp_header_len + dns_reply.len()) as u16;
        packet[udp_offset + 4..udp_offset + 6].copy_from_slice(&udp_len.to_be_bytes());
        packet[udp_offset + 6..udp_offset + 8].copy_from_slice(&0u16.to_be_bytes()); // Optional UDP checksum in IPv4
        
        // --- DNS REPLY PAYLOAD ---
        packet[udp_offset + 8..].copy_from_slice(dns_reply);
        
        Some(packet)
    } else if src_ip.len() == 16 && dst_ip.len() == 16 {
        // IPv6 Frame Assembly
        let ip_header_len = 40;
        let udp_header_len = 8;
        let payload_len = udp_header_len + dns_reply.len();
        let total_packet_len = ip_header_len + payload_len;
        
        let mut packet = vec![0u8; total_packet_len];
        
        // --- IPv6 HEADER ---
        packet[0] = 0x60; // Version 6
        packet[1] = 0x00;
        packet[2] = 0x00;
        packet[3] = 0x00; // Flow label
        packet[4..6].copy_from_slice(&(payload_len as u16).to_be_bytes());
        packet[6] = 17; // Next Header: UDP (17)
        packet[7] = 64; // Hop Limit
        
        // Swap IPs
        packet[8..24].copy_from_slice(dst_ip);
        packet[24..40].copy_from_slice(src_ip);
        
        // --- UDP HEADER ---
        let udp_offset = ip_header_len;
        packet[udp_offset..udp_offset + 2].copy_from_slice(&dst_port.to_be_bytes()); // Src Port
        packet[udp_offset + 2..udp_offset + 4].copy_from_slice(&src_port.to_be_bytes()); // Dst Port
        let udp_len = (udp_header_len + dns_reply.len()) as u16;
        packet[udp_offset + 4..udp_offset + 6].copy_from_slice(&udp_len.to_be_bytes());
        packet[udp_offset + 6..udp_offset + 8].copy_from_slice(&0u16.to_be_bytes()); // UDP Checksum optional placeholder
        
        // --- DNS REPLY PAYLOAD ---
        packet[udp_offset + 8..].copy_from_slice(dns_reply);
        
        Some(packet)
    } else {
        None
    }
}

fn calculate_ipv4_checksum(header: &[u8]) -> u16 {
    let mut sum = 0u32;
    for i in (0..header.len()).step_by(2) {
        let word = ((header[i] as u32) << 8) | (header[i + 1] as u32);
        sum += word;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}
