// Kotlin's WASI launcher does not preopen a directory for filesystem tests.
// Expose only the test task's writable build directory as the relative root.
const wasi = require("node:wasi");
const { syncBuiltinESMExports } = require("node:module");
const directory = process.env.AUDIOKODEC_WASI_TEST_DIRECTORY;
if (!directory) throw new Error("the WASI test directory was not configured");

const OriginalWASI = wasi.WASI;
wasi.WASI = class extends OriginalWASI {
    constructor(options) {
        super({ ...options, preopens: { ".": directory } });
    }
};
syncBuiltinESMExports();
