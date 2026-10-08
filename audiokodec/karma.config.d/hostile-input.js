// Exhaustive malformed-input sweeps take longer than Mocha's default limit.
config.set({
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, config.client && config.client.mocha, { timeout: 600000 })
    }),
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 600000,
    browserSocketTimeout: 600000,
    pingTimeout: 600000,
    failOnEmptyTestSuite: true
});
