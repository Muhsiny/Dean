const esbuild = require('esbuild');

esbuild.buildSync({
  entryPoints: ['src/neon-bridge.js'],
  bundle: true,
  minify: true,
  platform: 'browser',
  format: 'iife',
  target: ['es2020'],
  outfile: 'public/neon-bridge.bundle.js',
  sourcemap: false,
  legalComments: 'none'
});

console.log('Neon bridge bundle built successfully.');
