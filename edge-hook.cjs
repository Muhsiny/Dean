'use strict';
const fs=require('node:fs');
const path=require('node:path');
const MARK='BU_BAMIYAN_V5_20260919';
function findFile(name,dir,depth=0){
  if(depth>5)return null;
  let ents;try{ents=fs.readdirSync(dir,{withFileTypes:true})}catch{return null}
  for(const e of ents){if(e.isFile()&&e.name===name)return path.join(dir,e.name)}
  for(const e of ents){if(!e.isDirectory()||['node_modules','.git','.cache','coverage'].includes(e.name))continue;const hit=findFile(name,path.join(dir,e.name),depth+1);if(hit)return hit}
  return null;
}
function appendAsset(target,source){
  if(!target||!source||!fs.existsSync(source))return false;
  try{const cur=fs.readFileSync(target,'utf8');if(cur.includes(MARK))return true;const add=fs.readFileSync(source,'utf8');fs.appendFileSync(target,'\n/* '+MARK+' */\n'+add+'\n','utf8');return true}catch{return false}
}
if(process.env.npm_lifecycle_event==='start'){
  const root=process.cwd();
  const css=findFile('beheshti.css',root),js=findFile('app.js',root);
  const okCss=appendAsset(css,path.join(root,'beheshti-v5.css'));
  const okJs=appendAsset(js,path.join(root,'beheshti-v5.js'));
  try{console.log('[BU Bamyan v5 loader]',{css,js,okCss,okJs})}catch{}
}