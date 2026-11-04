# BlockAds local copy

Based on netstack-smoltcp 0.2.4 from the local Cargo registry, preserving its
existing relay, backpressure and cooperative-yield fixes. Original source and
license notices are retained. The manifest omits example-only dependencies.

Local change: when smoltcp reports no TCP deadline, wait on the existing Notify
instead of polling every 5 ms. Inbound packets, application writes, reads that
free buffer space, shutdown and stream drop already signal this Notify. Keep
finite TCP deadlines and cooperative yields for immediate work unchanged.

Regression tests cover no timer wakes on an idle stack, notification wakeups
(including a signal sent before waiting), and TCP timer deadlines. Run them with:

```sh
cargo test --manifest-path nativecore/Cargo.toml --locked --workspace -- --test-threads=1
```

This removes an idle polling source; battery savings on a physical Android device
have not been measured.
