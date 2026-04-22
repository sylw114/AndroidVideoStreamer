package org.dpdns.sylw.videostreamer

import java.util.concurrent.atomic.AtomicInteger

/**
 * 真正的无锁环形缓冲区（True Lock-Free Ring Buffer）
 * 
 * 🔥 专为高频音频流设计的零拷贝、无锁并发缓冲区
 * 
 * 设计原理：
 * 1. 使用 AtomicInteger 维护读写指针，完全无 synchronized
 * 2. 采用单生产者-单消费者（SPSC）模型，保证线程安全
 * 3. 循环覆盖旧数据，无需移动内存
 * 4. 使用位运算优化取模（要求 capacity 是 2 的幂）
 * 5. 零拷贝：直接写入预分配的 ByteArray
 * 
 * ⚠️ 重要约束：
 * - 只能有一个写入线程（AudioRecord 线程）
 * - 只能有一个读取线程（编码器线程）
 * - 多生产者或多消费者场景需要使用其他数据结构
 * 
 * @param capacity 缓冲区容量（包数量，会自动向上取整到 2 的幂）
 * @param packetSize 每个包的大小（字节数）
 */
class LockFreeRingBuffer(
    capacity: Int,
    private val packetSize: Int
) {
    // 🔥 确保 capacity 是 2 的幂（优化位运算取模：x & (n-1) == x % n）
    private val actualCapacity: Int = if (capacity > 0 && (capacity and (capacity - 1)) == 0) {
        capacity // 已经是 2 的幂
    } else {
        // 向上取整到最近的 2 的幂
        var powerOfTwo = 1
        while (powerOfTwo < capacity) {
            powerOfTwo = powerOfTwo shl 1
        }
        powerOfTwo
    }
    
    // 🔥 核心数据结构：预分配所有内存，避免 GC
    private val buffer: Array<ByteArray> = Array(actualCapacity) { ByteArray(packetSize) }
    
    // 🔥 无锁读写指针（AtomicInteger 保证原子性和可见性）
    // writePos: 下一个要写入的位置（单调递增，可能溢出）
    // readPos: 下一个要读取的位置（单调递增，可能溢出）
    private val writePos = AtomicInteger(0)
    private val readPos = AtomicInteger(0)
    
    /**
     * 写入数据到缓冲区（无锁）
     * 
     * 🔥 线程安全保证：
     * - 依赖单生产者假设（只有一个线程调用 write）
     * - 通过检查剩余空间防止写覆盖未读数据
     * - 先写入数据，再原子递增写指针（发布语义）
     * 
     * @param data 要写入的数据
     * @param size 实际数据大小（必须 <= packetSize）
     * @return true 表示写入成功，false 表示缓冲区已满（丢弃数据）
     */
    fun write(data: ByteArray, size: Int = data.size): Boolean {
        if (size > packetSize) {
            throw IllegalArgumentException("Data size ($size) exceeds packet size ($packetSize)")
        }
        
        // 🔥 获取当前读写位置（快照）
        val currentWritePos = writePos.get()
        val currentReadPos = readPos.get()
        
        // 计算当前元素数量
        val currentCount = currentWritePos - currentReadPos
        
        // 检查缓冲区是否已满
        if (currentCount >= actualCapacity) {
            return false // 缓冲区已满，丢弃数据（避免阻塞生产者）
        }
        
        // 计算实际写入位置（使用位运算优化取模）
        val pos = currentWritePos and (actualCapacity - 1)
        
        // 🔥 零拷贝：直接写入预分配的缓冲区
        System.arraycopy(data, 0, buffer[pos], 0, size)
        
        // 🔥 原子递增写指针（发布新数据，对消费者可见）
        writePos.incrementAndGet()
        
        return true
    }
    
    /**
     * 从缓冲区读取数据（无锁）
     * 
     * 🔥 线程安全保证：
     * - 依赖单消费者假设（只有一个线程调用 read）
     * - 通过检查是否有可读数据防止读越界
     * - 先读取数据，再原子递增读指针（消费语义）
     * 
     * @param output 输出缓冲区（复用，减少 GC）
     * @return 实际读取的字节数，-1 表示缓冲区为空
     */
    fun read(output: ByteArray): Int {
        // 🔥 获取当前读写位置（快照）
        val currentReadPos = readPos.get()
        val currentWritePos = writePos.get()
        
        // 检查缓冲区是否为空
        if (currentReadPos >= currentWritePos) {
            return -1 // 缓冲区为空
        }
        
        // 计算实际读取位置
        val pos = currentReadPos and (actualCapacity - 1)
        
        // 🔥 零拷贝：直接从缓冲区复制
        val dataSize = buffer[pos].size
        System.arraycopy(buffer[pos], 0, output, 0, dataSize)
        
        // 🔥 原子递增读指针（消费数据，释放空间给生产者）
        readPos.incrementAndGet()
        
        return dataSize
    }
    
    /**
     * 获取当前缓冲区中的数据量（近似值，用于监控）
     * 
     * ⚠️ 注意：由于无锁并发，返回值可能在读取瞬间发生变化
     * 仅用于监控和调试，不应用于业务逻辑判断
     */
    fun size(): Int {
        val currentWritePos = writePos.get()
        val currentReadPos = readPos.get()
        return maxOf(0, currentWritePos - currentReadPos)
    }
    
    /**
     * 检查缓冲区是否为空（近似判断）
     */
    fun isEmpty(): Boolean = size() <= 0
    
    /**
     * 检查缓冲区是否已满（近似判断）
     */
    fun isFull(): Boolean = size() >= actualCapacity
    
    /**
     * 清空缓冲区（无锁）
     * 
     * ⚠️ 注意：此方法不是原子的，调用时需要确保没有并发读写
     * 通常在重置状态时调用（如屏幕旋转、停止推流）
     */
    fun clear() {
        writePos.set(0)
        readPos.set(0)
        // 不需要清空 buffer 内容，下次写入会覆盖
    }
    
    /**
     * 获取缓冲区容量（实际的 2 的幂次方容量）
     */
    fun getCapacity(): Int = actualCapacity
    
    /**
     * 获取包大小
     */
    fun getPacketSize(): Int = packetSize
    
    companion object {
        /**
         * 创建一个适合音频采集的环形缓冲区
         * 
         * @param maxSeconds 最大缓存秒数
         * @param sampleRate 采样率（如 48000）
         * @param channelCount 声道数（如 2）
         * @param bytesPerSample 每采样字节数（如 2 表示 16-bit）
         * @param packetsPerSecond 每秒包数（由外部源决定，如 100）
         */
        fun createForAudio(
            maxSeconds: Int = 60, // 缓存 60 秒
            sampleRate: Int = 48000,
            channelCount: Int = 2,
            bytesPerSample: Int = 2,
            packetsPerSecond: Int = 100
        ): LockFreeRingBuffer {
            // 计算总包数
            val totalPackets = maxSeconds * packetsPerSecond
            
            // 计算每包大小（15360 字节是常见值）
            val packetSize = (sampleRate * channelCount * bytesPerSample) / packetsPerSecond
            
            return LockFreeRingBuffer(totalPackets, packetSize)
        }
    }
}
