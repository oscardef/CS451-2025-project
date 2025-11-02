package cs451.net;

import java.io.IOException;
import java.net.*;

/**
 * UdpChannel — a tiny, single-socket UDP wrapper.
 * Owns one DatagramSocket and exposes send/receive with minimal overhead.
 */
public final class UdpChannel implements AutoCloseable {

    private final DatagramSocket socket;

    private UdpChannel(DatagramSocket socket) {
        this.socket = socket;
    }

    /**
     * Bind to local UDP port with large buffers (reduce OS drops).
     *
     * @param localPort       port to bind
     * @param soTimeoutMillis receive timeout (0 = block)
     */
    public static UdpChannel bind(int localPort, int soTimeoutMillis) throws SocketException {
        DatagramSocket s = new DatagramSocket(null); // unbound
        s.setReuseAddress(true);
        s.setSoTimeout(soTimeoutMillis);             // 0 for blocking receive

        // Big buffers to avoid kernel-level packet drops
        s.setReceiveBufferSize(8 * 1024 * 1024);
        s.setSendBufferSize(8 * 1024 * 1024);

        s.bind(new InetSocketAddress(localPort));
        return new UdpChannel(s);
    }

    /** Send raw bytes to a remote (ip,port). */
    public void send(byte[] bytes, int length, InetAddress dstIp, int dstPort) throws IOException {
        DatagramPacket pkt = new DatagramPacket(bytes, length, dstIp, dstPort);
        socket.send(pkt);
    }

    /**
     * Receive some bytes into the provided buffer. Blocks according to SO_TIMEOUT.
     * @return number of bytes received
     */
    public int receive(byte[] buf, SenderRef outSender) throws IOException {
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt); // blocks until packet arrives or timeout
        if (outSender != null) {
            outSender.address = pkt.getAddress();
            outSender.port = pkt.getPort();
        }
        return pkt.getLength();
    }

    /** Expose socket if needed. */
    public DatagramSocket socket() { return socket; }

    @Override
    public void close() {
        socket.close();
    }

    /** Mutable holder for last sender; lets caller reuse an instance to avoid allocs. */
    public static final class SenderRef {
        public InetAddress address;
        public int port;
    }
}
