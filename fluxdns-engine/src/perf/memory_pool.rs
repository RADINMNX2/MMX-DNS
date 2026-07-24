/// Zero-Allocation Memory Management System for Zero-Impact Background Engine (ZIBE).
/// Manages pre-allocated static ring/stack buffers to avoid heap allocation stutters.

use once_cell::sync::Lazy;
use crossbeam_queue::ArrayQueue;
use std::ops::{Deref, DerefMut};

pub const BUFFER_SIZE: usize = 65535;

/// Lock-free pre-allocated buffer memory pool that lives for the lifetime of the application.
pub struct MemoryPool {
    pool: ArrayQueue<Box<[u8; BUFFER_SIZE]>>,
}

impl MemoryPool {
    /// Creates a memory pool with specified pre-allocated capacity
    pub fn new(capacity: usize) -> Self {
        let pool = ArrayQueue::new(capacity.max(256));
        for _ in 0..capacity {
            let _ = pool.push(Box::new([0u8; BUFFER_SIZE]));
        }
        Self { pool }
    }

    /// Acquires a pre-allocated buffer lock-free from the queue wrapped in an automatic `PoolGuard`.
    pub fn acquire(&self) -> PoolGuard {
        let buf = self.pool.pop().unwrap_or_else(|| Box::new([0u8; BUFFER_SIZE]));
        PoolGuard { buf: Some(buf) }
    }

    /// Releases a buffer back into the lock-free pool queue.
    pub fn release(&self, buf: Box<[u8; BUFFER_SIZE]>) {
        let _ = self.pool.push(buf);
    }
}

/// Smart pointer that dereferences to a raw mutable byte slice and returns the buffer to the static `MEMORY_POOL` upon drop.
pub struct PoolGuard {
    buf: Option<Box<[u8; BUFFER_SIZE]>>,
}

impl Deref for PoolGuard {
    type Target = [u8; BUFFER_SIZE];
    fn deref(&self) -> &Self::Target {
        self.buf.as_ref().unwrap()
    }
}

impl DerefMut for PoolGuard {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.buf.as_mut().unwrap()
    }
}

impl Drop for PoolGuard {
    fn drop(&mut self) {
        if let Some(buf) = self.buf.take() {
            MEMORY_POOL.release(buf);
        }
    }
}

/// Task structure to store all parsed components in-place in a single pooled block.
pub struct PooledTask {
    pub guard: PoolGuard,
    pub src_ip_len: usize,
    pub dst_ip_len: usize,
    pub src_port: u16,
    pub dst_port: u16,
    pub payload_len: usize,
}

impl PooledTask {
    /// Copies components into a pre-allocated memory pool block in a single pass.
    pub fn new(
        src_ip: &[u8],
        dst_ip: &[u8],
        src_port: u16,
        dst_port: u16,
        payload: &[u8],
    ) -> Self {
        let mut guard = MEMORY_POOL.acquire();
        let src_len = src_ip.len();
        let dst_len = dst_ip.len();
        let pay_len = payload.len();

        // Safety check to ensure we don't overflow the preallocated buffer (64KB is extremely safe for MTU-sized IP/UDP)
        assert!(src_len + dst_len + pay_len <= BUFFER_SIZE, "ZIBE: Packet size exceeds pre-allocated memory boundary");

        guard[0..src_len].copy_from_slice(src_ip);
        guard[src_len..src_len + dst_len].copy_from_slice(dst_ip);
        guard[src_len + dst_len..src_len + dst_len + pay_len].copy_from_slice(payload);

        Self {
            guard,
            src_ip_len: src_len,
            dst_ip_len: dst_len,
            src_port,
            dst_port,
            payload_len: pay_len,
        }
    }

    /// Borrows the source IP from the pooled block.
    pub fn src_ip(&self) -> &[u8] {
        &self.guard[0..self.src_ip_len]
    }

    /// Borrows the destination IP from the pooled block.
    pub fn dst_ip(&self) -> &[u8] {
        &self.guard[self.src_ip_len..self.src_ip_len + self.dst_ip_len]
    }

    /// Borrows the payload from the pooled block.
    pub fn payload(&self) -> &[u8] {
        let start = self.src_ip_len + self.dst_ip_len;
        &self.guard[start..start + self.payload_len]
    }
}

/// Thread-safe global static memory pool initialized once with 256 buffers (totaling 16.7MB of static resident memory).
pub static MEMORY_POOL: Lazy<MemoryPool> = Lazy::new(|| MemoryPool::new(256));

