package com.verifone.cat.collect;

/** A file in a petroapps commit: its real git path (case as committed) and its blob object ID. */
public record Blob(String path, String sha) {}
