package io.github.yuroyami.audiokodec

/**
 * A few lines of Python over libmpg123's documented API, which decode an MPEG
 * file to interleaved 32-bit floats and print the rate, the channel count and
 * the length libmpg123 reports after its own gapless trimming.
 *
 * Arguments: input file, output file, rate, channels. Exits with 2 when the
 * library cannot be found. Signatures and constants are from the public
 * documentation at mpg123.org/api/, not from the library's implementation.
 */
internal const val MPG123_BRIDGE = """
import array, ctypes as c, ctypes.util, os, sys
name = os.environ.get('AUDIOKODEC_MPG123_LIBRARY') or ctypes.util.find_library('mpg123')
if not name: sys.exit(2)
try: lib = c.CDLL(name)
except OSError:
    if os.environ.get('AUDIOKODEC_MPG123_LIBRARY'): raise
    sys.exit(2)
lib.mpg123_new.argtypes = [c.c_char_p, c.POINTER(c.c_int)]
lib.mpg123_new.restype = c.c_void_p
lib.mpg123_delete.argtypes = [c.c_void_p]
lib.mpg123_format_none.argtypes = [c.c_void_p]
lib.mpg123_format.argtypes = [c.c_void_p, c.c_long, c.c_int, c.c_int]
lib.mpg123_open.argtypes = [c.c_void_p, c.c_char_p]
lib.mpg123_getformat.argtypes = [c.c_void_p, c.POINTER(c.c_long), c.POINTER(c.c_int), c.POINTER(c.c_int)]
lib.mpg123_read.argtypes = [c.c_void_p, c.c_void_p, c.c_size_t, c.POINTER(c.c_size_t)]
lib.mpg123_scan.argtypes = [c.c_void_p]
lib.mpg123_length.argtypes = [c.c_void_p]
lib.mpg123_length.restype = c.c_int64
assert lib.mpg123_init() == 0
error = c.c_int()
handle = lib.mpg123_new(None, c.byref(error))
assert handle, error.value
rate, channels = int(sys.argv[3]), int(sys.argv[4])
try:
    assert lib.mpg123_format_none(handle) == 0
    assert lib.mpg123_format(handle, rate, 3, 0x200) == 0
    assert lib.mpg123_open(handle, os.fsencode(sys.argv[1])) == 0
    actual_rate, actual_channels, encoding = c.c_long(), c.c_int(), c.c_int()
    assert lib.mpg123_getformat(handle, c.byref(actual_rate), c.byref(actual_channels), c.byref(encoding)) == 0
    assert (actual_rate.value, actual_channels.value, encoding.value) == (rate, channels, 0x200), (actual_rate.value, actual_channels.value, encoding.value)
    assert lib.mpg123_scan(handle) == 0
    print(rate, channels, lib.mpg123_length(handle))
    buffer, done = c.create_string_buffer(65536), c.c_size_t()
    with open(sys.argv[2], 'wb') as output:
        while True:
            result = lib.mpg123_read(handle, buffer, len(buffer), c.byref(done))
            values = array.array('f')
            values.frombytes(buffer.raw[:done.value])
            if sys.byteorder != 'little': values.byteswap()
            output.write(values.tobytes())
            if result == -12: break
            if result == -11:
                assert lib.mpg123_getformat(handle, c.byref(actual_rate), c.byref(actual_channels), c.byref(encoding)) == 0
                assert (actual_rate.value, actual_channels.value, encoding.value) == (rate, channels, 0x200)
            else: assert result == 0, result
finally: lib.mpg123_delete(handle)
"""
