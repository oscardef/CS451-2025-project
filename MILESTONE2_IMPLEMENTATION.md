# Milestone 2: FIFO Broadcast

## Architecture

```
FifoBroadcast → URB → PerfectLinks → StubbornLinks → FairLossLinks → UDP
```

## Components

### Message (`cs451.broadcast.Message`)
Binary format: `[origin(4B) | seq(4B)]`

### URB (`cs451.broadcast.urb`)
Flooding-based uniform reliable broadcast. On first receipt, re-broadcasts to all. Uses `seen` and `delivered` sets for deduplication.

### FifoBroadcast (`cs451.broadcast.fifo`)
Per-sender ordering. Maintains `nextExpected[sender]` and buffers out-of-order messages in `pending[sender]` until they can be delivered in sequence.

## Config Detection

- One integer: FIFO Broadcast mode
- Two integers: Perfect Links mode

## Testing

```bash
cd template_java

# Run 3 processes
./run.sh --id 1 --hosts ../example/hosts --output ../example/output/1.output ../example/configs/fifo-broadcast.config
./run.sh --id 2 --hosts ../example/hosts --output ../example/output/2.output ../example/configs/fifo-broadcast.config
./run.sh --id 3 --hosts ../example/hosts --output ../example/output/3.output ../example/configs/fifo-broadcast.config
```

Output format: `b seq` for broadcast, `d sender seq` for delivery.
