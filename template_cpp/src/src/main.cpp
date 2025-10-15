#include <chrono>
#include <iostream>
#include <thread>

#include "parser.hpp"
#include "hello.h"
#include <signal.h>

#include <arpa/inet.h>   // htons/ntohs, htonl/ntohl, sockaddr_in
#include <sys/socket.h>  // socket, bind, setsockopt
#include <unistd.h>      // close()
#include <stdexcept>     // std::runtime_error
#include <cerrno>        // errno
#include <cstring>       // std::strerror

// ip_be and port_be are in "network byte order" (big-endian).
// Parser::Host already gives you ip and port in network order, so you can pass them directly.
int make_bound_socket(in_addr_t ip_be, uint16_t port_be) {
  // fd: POSIX file descriptor representing the UDP socket returned by socket()
  int fd = ::socket(AF_INET, SOCK_DGRAM, 0);
  if (fd < 0) {
    throw std::runtime_error(std::string("socket() failed: ") + std::strerror(errno));
  }

  // yes: integer flag used with setsockopt to enable SO_REUSEADDR (allow quick rebinds)
  int yes = 1;
  ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

  // me: local address we want to bind to (IPv4)
  // - sin_family: address family (AF_INET = IPv4)
  // - sin_addr.s_addr: local interface IP in network byte order
  // - sin_port: local UDP port in network byte order
  sockaddr_in me{};
  me.sin_family = AF_INET;
  me.sin_addr.s_addr = ip_be;  // already network order
  me.sin_port = port_be;       // already network order

  // bind the socket to (ip, port) so peers can send to us and we can receive ACKs on this port
  if (::bind(fd, reinterpret_cast<sockaddr*>(&me), sizeof(me)) < 0) {
    int e = errno;
    ::close(fd);
    throw std::runtime_error(std::string("bind() failed: ") + std::strerror(e));
  }

  return fd; // caller owns fd and should close(fd) on shutdown
}
static void stop(int) {
  // reset signal handlers to default
  signal(SIGTERM, SIG_DFL);
  signal(SIGINT, SIG_DFL);

  // immediately stop network packet processing
  std::cout << "Immediately stopping network packet processing.\n";

  // write/flush output file if necessary
  std::cout << "Writing output.\n";

  // exit directly from signal handler
  exit(0);
}

int main(int argc, char **argv) {
  signal(SIGTERM, stop);
  signal(SIGINT, stop);

  // `true` means that a config file is required.
  // Call with `false` if no config file is necessary.
  bool requireConfig = true;

  Parser parser(argc, argv);
  parser.parse();

  hello();
  std::cout << std::endl;

  std::cout << "My PID: " << getpid() << "\n";
  std::cout << "From a new terminal type `kill -SIGINT " << getpid() << "` or `kill -SIGTERM "
            << getpid() << "` to stop processing packets\n\n";

  std::cout << "My ID: " << parser.id() << "\n\n";

  std::cout << "List of resolved hosts is:\n";
  std::cout << "==========================\n";
  auto hosts = parser.hosts();
  for (auto &host : hosts) {
    std::cout << host.id << "\n";
    std::cout << "Human-readable IP: " << host.ipReadable() << "\n";
    std::cout << "Machine-readable IP: " << host.ip << "\n";
    std::cout << "Human-readbale Port: " << host.portReadable() << "\n";
    std::cout << "Machine-readbale Port: " << host.port << "\n";
    std::cout << "\n";
  }
  std::cout << "\n";

  std::cout << "Path to output:\n";
  std::cout << "===============\n";
  std::cout << parser.outputPath() << "\n\n";

  std::cout << "Path to config:\n";
  std::cout << "===============\n";
  std::cout << parser.configPath() << "\n\n";

  std::cout << "Doing some initialization...\n\n";

  std::cout << "Broadcasting and delivering messages...\n\n";

  // After a process finishes broadcasting,
  // it waits forever for the delivery of messages.
  while (true) {
    std::this_thread::sleep_for(std::chrono::hours(1));
  }

  return 0;
}
