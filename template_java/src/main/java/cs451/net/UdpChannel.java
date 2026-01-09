package cs451.net;

import java.io.IOException;
import java.net.*;

/**
 * Simple UDP socket wrapper with large buffers.
 */
public final class UdpChannel implements AutoCloseable {

    private final DatagramSocket socket;

    private UdpChannel(DatagramSocket socket) {
        this.socket = socket;
    }

    public static UdpChannel bind(int localPort, int soTimeoutMillis) throws SocketException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.setSoTimeout(soTimeoutMillis);
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

    public int receive(byte[] buf, SenderRef outSender) throws IOException {
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
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

    public static final class SenderRef {
        public InetAddress address;
        public int port;
    }
}
