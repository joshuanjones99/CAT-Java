package com.verifone.cat.provision.transport;

/**
 * Receives each connection a reverse port forward accepted on the box. The Python's paramiko callback
 * ({@code handler(channel, origin, server)}); {@link ForwardedConnection#port()} is {@code server[1]}.
 *
 * <p>A transport may call this on its own dispatcher thread (paramiko does; the in-memory transport calls it on the
 * connecting thread), so it must return promptly: blocking work (DNS, the CONNECT read, the outbound connect) goes to
 * a thread of the handler's own. The handler owns the connection and must close it.
 */
@FunctionalInterface
public interface ForwardHandler {

    void accept(ForwardedConnection connection);
}
