# Decode early Vorbis floor-zero streams

Use the ordinary Ogg APIs. AudioKodec decodes both Vorbis floor types without a
caller option. Early beta recordings can carry floor type 0, which describes
the spectral envelope through line spectral pairs rather than line segments.

```kotlin
AudioKodec.open(oggBytes).use { decoder ->
    decoder.seek(decoder.format.sampleRate.toLong())
    val block = decoder.nextFrame()
    println(block?.positionFrames)
}
```

Vector lookup types one and two are supported. Each vector accumulates the
previous vector's final coefficient. Extra values in the last vector are
discarded; even a codebook vector larger than the floor order does not grow
the per-channel scratch. The setup's unsigned amplitude field accepts up to
63 bits. A zero amplitude disables that channel's floor for the packet.
A positive amplitude with zero amplitude offset produces a unity curve.

As the Vorbis specification requires, a packet that ends while reading the
floor returns unused status. Invalid book selectors, scalar-only floor books,
zero order/rate/Bark-map size and non-finite coefficients or curves raise
`CorruptStreamException`. Scratch allocations respect
`DecodeOptions.maxAllocationBytes`. Floor type 1 follows its existing path.

Duration, seeking, buffer reuse, chained links and tag editing use the existing
Vorbis and Ogg behaviour. Tag edits retain all coded audio packets and decoded
samples. Each chained link can supply a different floor-zero setup.

The implementation follows section 6.2 of the Vorbis I specification, including
the corrected Bark equation. Common tests compute the published odd/even
products independently. Synthetic complete streams compare against FFmpeg's
float decoder at maximum sample error `1e-4`, with RMS below `1e-5` or `2e-5`
for the maximum order of 255. The qualified native FFmpeg decoder rejects
lookup type two; its libvorbis wrapper checks those streams at its 16-bit
output precision, with the same peak bound and RMS below `3e-5`.
No reference decoder implementation source is read.
