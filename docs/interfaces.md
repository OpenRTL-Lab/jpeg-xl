<!-- OpenRTL Lab | JPEG XL | version 1.0.0 | SPDX-License-Identifier: MIT -->
# Architecture and interfaces

[简体中文](interfaces.zh-CN.md)

## System boundary

`FastNativeEncoder` reads raw pixels and writes complete JPEG XL raw codestreams. A CPU submits descriptors; the encoder performs prediction, statistics, codebook construction and bit packing. The CPU does not supply encoded answers or intermediate codebooks. Two internal processing contexts and four retained job/result slots overlap work; they are not independent full-rate encoder instances.

Inputs are tightly interleaved Gray, GrayA, RGB or RGBA, eight bits per channel, with no row stride. Width and height are each 1–256. Output begins with the raw codestream signature `FF 0A`; no external file container is added. Transparent-pixel color samples are preserved.

## Native ports

All directions are relative to the encoder. The top-level `clock` has an active-low asynchronous `rst_n`. Each channel uses valid/ready; a transfer occurs only when both are high on the rising edge. A stalled producer must hold valid and payload stable.

| Channel | Direction | Payload |
|---|---|---|
| command | input | tag32, width9, height9, channels3, source33, destination33, sourceSpan24, destinationSpan24, outputLimit24 |
| result | output | tag32, status3, outputBytes24, faultAddress33, acceptedCycle64, firstFileWriteCycle64, retiredCycle64, readRequests13, writeRequests15 |
| cancel | input | tag32 |
| cancelAck | output | tag32, found1 |
| read | output | id4, address33 |
| readResponse | input | id4, data512, fault1 |
| write | output | id4, address33, data512 |
| writeResponse | input | id4, fault1 |
| quiescent | output | no retained jobs, pending results or outstanding transactions |

The actual flattened SV port names use `io_command_bits_tag` and analogous names. The native interface is neither AXI nor TileLink; adapters translate it. IDs may complete out of order, but a live ID cannot be reused before its response. Every memory request is one aligned 64-byte block; low byte lanes correspond to lower addresses.

## Memory ownership and completion

Addresses, spans and limits are in bytes. Input/output starts and spans must be 64-byte aligned. The source span must cover `roundUp(width*height*channels,64)`; destination span and output limit must be nonzero, and the limit cannot exceed the destination span. Address-plus-span must fit in 33 bits. Source and destination spans cannot overlap, including spans owned by other retained jobs; two live jobs cannot share the same input allocation. Buffers remain owned until the result has been consumed, and cancellation is not permission to reclaim outstanding DMA buffers early.

Results can be out of submission order; identify them by tag. Invalid commands may be accepted into a result slot and then return an error without DMA. Read the status before using output length. The full result-status enumeration and software checks are authoritative in `software/jxl_mmio.h`. Complete output is validated up to `outputBytes`, with zero padding to the last 64-byte write boundary.

## Digital SoC adapters

`integration/src/main/scala/jpegxl/platform/` provides a CSR controller and a 512-bit TileLink DMA adapter. CSR addresses are 32-bit accesses in a 4 KiB region, default base `0x10040000`, ID `0x4a584c31`, ABI `0x00010000`. The register map and helper functions are in `software/jxl_mmio.h`. The DMA bridge restricts accesses to its declared DDR window and propagates denied/corrupt responses. An interrupt output is available; polling is the FPGA-tested software path, not an interrupt validation claim.

The integration trait connects control to the system bus through fragment/width adapters, DMA through the coherent system bus, and completion interrupts to the subsystem interrupt bus. The external system supplies clocks, reset synchronization, memory mapping/coherence, interrupt assignment, CPU startup and physical memory. See [build and integration](build.md). The other bounded encoder/decoder sources use `NativeIO`, a separate byte-stream contract; do not connect them as drop-in replacements for `FastNativeEncoder`.
