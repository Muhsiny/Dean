const fs = require('fs');
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

let app = fs.readFileSync('public/app-v5.js', 'utf8');
app = app.replace(
  "const wk=CURRICULUM[w-1],ready=wk.words.every(x=>state.vocab[x]?.definition),qs=ready?vocabQuestions(w):[]",
  "const wk=CURRICULUM[w-1],defined=wk.words.filter(x=>state.vocab[x]?.definition).length,ready=defined>=4,qs=ready?vocabQuestions(w):[]"
);
fs.writeFileSync('public/app-v5.bundle.js', app);

console.log('Beheshti Academy v5 production assets built successfully.');
