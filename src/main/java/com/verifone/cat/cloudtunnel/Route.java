package com.verifone.cat.cloudtunnel;

/**
 * One endpoint, the address the Commander dials for it, and its forward port on the controller's side.
 *
 * @param endpoint    the cloud host and port
 * @param localIp     the endpoint's own IP, or the loopback alias its name is pointed at
 * @param forwardPort the Commander's loopback port the reverse forward listens on
 */
public record Route(Endpoint endpoint, String localIp, int forwardPort) {}
