pub mod encoder;
pub mod decoder;

use std::sync::Arc;

lazy_static::lazy_static! {
    /// Galois Field 2^8 Exponential lookup table.
    pub static ref GF_EXP: [u8; 512] = {
        let mut exp = [0u8; 512];
        let mut x = 1u16;
        for i in 0..255 {
            exp[i] = x as u8;
            exp[i + 255] = x as u8;
            let mut next = x << 1;
            if next & 0x100 != 0 {
                next ^= 0x11d; // Primitive polynomial: x^8 + x^4 + x^3 + x^2 + 1
            }
            x = next;
        }
        exp[255] = exp[0];
        exp[510] = exp[255];
        exp[511] = exp[256];
        exp
    };

    /// Galois Field 2^8 Logarithmic lookup table.
    pub static ref GF_LOG: [u8; 256] = {
        let mut log = [0u8; 256];
        let mut x = 1u16;
        for i in 0..255 {
            log[x as usize] = i as u8;
            let mut next = x << 1;
            if next & 0x100 != 0 {
                next ^= 0x11d;
            }
            x = next;
        }
        log
    };
}

/// Addition in GF(256) is equivalent to bitwise XOR.
#[inline(always)]
pub fn gf_add(a: u8, b: u8) -> u8 {
    a ^ b
}

/// Subtraction in GF(256) is also equivalent to bitwise XOR.
#[inline(always)]
pub fn gf_sub(a: u8, b: u8) -> u8 {
    a ^ b
}

/// Multiplication in GF(256) using precomputed log/exp tables.
#[inline(always)]
pub fn gf_mul(a: u8, b: u8) -> u8 {
    if a == 0 || b == 0 {
        0
    } else {
        let idx = (GF_LOG[a as usize] as usize) + (GF_LOG[b as usize] as usize);
        GF_EXP[idx]
    }
}

/// Division in GF(256) using precomputed log/exp tables.
#[inline(always)]
pub fn gf_div(a: u8, b: u8) -> u8 {
    if a == 0 {
        0
    } else if b == 0 {
        0 // Safe division fallback
    } else {
        let idx = (GF_LOG[a as usize] as usize) + 255 - (GF_LOG[b as usize] as usize);
        GF_EXP[idx]
    }
}

/// Invert a GF(256) element.
#[inline(always)]
pub fn gf_inv(val: u8) -> u8 {
    if val == 0 {
        0
    } else {
        GF_EXP[255 - GF_LOG[val as usize] as usize]
    }
}

/// Performs in-place matrix inversion using Gauss-Jordan elimination on a square GF(256) matrix.
pub fn invert_matrix(matrix: &mut [Vec<u8>]) -> Option<Vec<Vec<u8>>> {
    let n = matrix.len();
    let mut inv = vec![vec![0u8; n]; n];
    for i in 0..n {
        inv[i][i] = 1;
    }

    for i in 0..n {
        // Find pivot
        if matrix[i][i] == 0 {
            let mut pivot_row = None;
            for r in (i + 1)..n {
                if matrix[r][i] != 0 {
                    pivot_row = Some(r);
                    break;
                }
            }
            if let Some(r) = pivot_row {
                matrix.swap(i, r);
                inv.swap(i, r);
            } else {
                return None; // Matrix is singular (non-invertible)
            }
        }

        let pivot = matrix[i][i];
        let inv_pivot = gf_inv(pivot);

        // Scale row i
        for c in 0..n {
            matrix[i][c] = gf_mul(matrix[i][c], inv_pivot);
            inv[i][c] = gf_mul(inv[i][c], inv_pivot);
        }

        // Eliminate column entries
        for r in 0..n {
            if r != i {
                let factor = matrix[r][i];
                if factor != 0 {
                    for c in 0..n {
                        matrix[r][c] = gf_sub(matrix[r][c], gf_mul(matrix[i][c], factor));
                        inv[r][c] = gf_sub(inv[r][c], gf_mul(inv[i][c], factor));
                    }
                }
            }
        }
    }

    Some(inv)
}

/// Complete 8-byte AetherUDP Forward Error Correction Header
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AetherFecHeader {
    pub magic: u16,        // Always 0xAE01 (AetherUDP Protocol Magic)
    pub block_id: u16,     // Monotonically increasing FEC Block ID
    pub packet_index: u8,  // Index in block: 0..K-1 are data, K..K+M-1 are parity
    pub payload_len: u16,  // Original variable packet length (since all blocks are zero-padded to equal length)
    pub reserved: u8,      // Reserved padding
}

impl AetherFecHeader {
    pub const SIZE: usize = 8;
    pub const MAGIC: u16 = 0xAE01;

    pub fn serialize(&self) -> [u8; Self::SIZE] {
        let mut buf = [0u8; Self::SIZE];
        buf[0..2].copy_from_slice(&self.magic.to_be_bytes());
        buf[2..4].copy_from_slice(&self.block_id.to_be_bytes());
        buf[4] = self.packet_index;
        buf[5..7].copy_from_slice(&self.payload_len.to_be_bytes());
        buf[7] = self.reserved;
        buf
    }

    pub fn deserialize(buf: &[u8]) -> Option<Self> {
        if buf.len() < Self::SIZE {
            return None;
        }
        let magic = u16::from_be_bytes([buf[0], buf[1]]);
        if magic != Self::MAGIC {
            return None;
        }
        let block_id = u16::from_be_bytes([buf[2], buf[3]]);
        let packet_index = buf[4];
        let payload_len = u16::from_be_bytes([buf[5], buf[6]]);
        let reserved = buf[7];
        Some(AetherFecHeader {
            magic,
            block_id,
            packet_index,
            payload_len,
            reserved,
        })
    }
}
