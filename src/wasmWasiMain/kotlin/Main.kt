import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

@OptIn(ExperimentalWasmInterop::class)
@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun wasiRawFdRead(fd: Int, iovPtr: Int, iovLen: Int, nreadPtr: Int): Int

fun main() {
    while (true) {
        val line = readLineFromStdin() ?: break
        println("Wasm received: $line")
    }
}

@OptIn(UnsafeWasmMemoryApi::class)
fun readLineFromStdin(): String? {
    val sb = StringBuilder()

    while (true) {
        var bytesRead = 0
        var readByte: Byte = 0
        var isError = false

        withScopedMemoryAllocator { allocator ->
            val buf = allocator.allocate(1)
            val iov = allocator.allocate(8)
            val nread = allocator.allocate(4)

            Pointer(iov.address).storeInt(buf.address.toInt())
            Pointer(iov.address + 4u).storeInt(1)

            val ret = wasiRawFdRead(
                fd = 0,
                iovPtr = iov.address.toInt(),
                iovLen = 1,
                nreadPtr = nread.address.toInt()
            )

            if (ret != 0) {
                isError = true
                return@withScopedMemoryAllocator
            }

            bytesRead = Pointer(nread.address).loadInt()
            if (bytesRead > 0) readByte = Pointer(buf.address).loadByte()
        }

        if (isError) return null
        if (bytesRead == 0) return if (sb.isEmpty()) null else sb.toString()

        val ch = readByte.toInt().and(0xFF).toChar()
        if (ch == '\n') return sb.toString()
        if (ch != '\r') sb.append(ch)
    }
}
