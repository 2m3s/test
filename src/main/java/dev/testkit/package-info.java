/**
 * Common (client+server) root for the test kit.
 *
 * <p>Deliberately almost empty: the harness is client-side and the world-building helper is a separate
 * server mod. This package exists so the common source set produces an output directory, which loom's
 * dev launcher expects to find on the classpath.
 */
package dev.testkit;
