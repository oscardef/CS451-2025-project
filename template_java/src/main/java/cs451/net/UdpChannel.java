package cs451.net;

import java.io.IOException;
import java.net.*;

/**
 * UdpChannel — a tiny, single-socket UDP wrapper.
 *
 * Responsibilities:
 *  - Own exactly one DatagramSocket bound to our configured port.
 *  - Provide send/receive methods with minimal ceremony.
 *  - No protocol logic here (no ACKs, no dedup (ignoring duplicate deliveries)). Pure transport.
 */
public final class UdpChannel implements AutoCloseable {

    private final DatagramSocket socket;

    /**
     * Construct an UNBOUND channel; caller must call bind().
     * Kept private; use the static factory to avoid half-initialized instances.
     */
    private UdpChannel(DatagramSocket socket) {
        this.socket = socket;
    }

    /**
     * Factory that creates a channel and binds it to the given local port.
     * Binds to 0.0.0.0:<port> so we listen on all local interfaces.
     *
     * @param localPort the UDP port to bind to (from HOSTS)
     * @param soTimeoutMillis receive timeout (0 = block forever for now)
     */
    public static UdpChannel bind(int localPort, int soTimeoutMillis) throws SocketException {
        DatagramSocket s = new DatagramSocket(null); // unbound
        s.setReuseAddress(true);
        s.setSoTimeout(soTimeoutMillis);             // 0 for blocking receive
        s.bind(new InetSocketAddress(localPort));
        return new UdpChannel(s);
    }
    
    /**
     * Send raw bytes to a remote (ip,port). Caller owns the buffer.
     */
    public void send(byte[] bytes, int length, InetAddress dstIp, int dstPort) throws IOException {
        DatagramPacket pkt = new DatagramPacket(bytes, length, dstIp, dstPort);
        socket.send(pkt);
    }

    /**
     * Receive some bytes into the provided buffer. Blocks according to SO_TIMEOUT.
     * @return number of bytes received, or -1 if truncated (shouldn't happen since we size buffers sensibly)
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

    /**
     * Expose socket (read-only) if you ever need low-level options in PL.
     */
    public DatagramSocket socket() { return socket; }

    @Override
    public void close() {
        socket.close();
    }

    /**
     * Small mutable holder for the sender of the last received datagram.
     * Avoids allocations by letting caller reuse a single instance.
     */
    public static final class SenderRef {
        public InetAddress address;
        public int port;
    }
}
