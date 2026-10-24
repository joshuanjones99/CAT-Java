package com.verifone.cat.provision.transport;

/** The outcome of one remote command: its exit status (-1 when the server sent none) and its output. */
public record CommandResult(int exitStatus, String stdout, String stderr) {

    public boolean ok() {
        return exitStatus == 0;
    }
}
