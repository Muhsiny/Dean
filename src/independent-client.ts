type ApiResponse<T=any>={data:T};
async function request<T=any>(method:string,url:string,payload?:unknown):Promise<ApiResponse<T>>{
  const headers:Record<string,string>={}; let body:BodyInit|undefined;
  if(payload!==undefined){headers['content-type']='application/json';body=JSON.stringify(payload);}
  const r=await fetch(url,{method,headers,body,credentials:'same-origin'});
  const text=await r.text(); let data:any=null; try{data=text?JSON.parse(text):null}catch{data=text}
  if(!r.ok){const e:any=new Error(data?.error||('HTTP '+r.status));e.response={status:r.status,data};throw e}
  return {data};
}
export const api={get:<T=any>(u:string)=>request<T>('GET',u),post:<T=any>(u:string,b?:unknown)=>request<T>('POST',u,b),put:<T=any>(u:string,b?:unknown)=>request<T>('PUT',u,b),delete:<T=any>(u:string)=>request<T>('DELETE',u)};
export const auth={
  isSignedIn:()=>false,
  getUser:async()=>({userId:'',email:'',name:''}),
  signIn:async()=>{throw Object.assign(new Error('admin_auth_not_configured'),{code:'admin_auth_not_configured'})},
  signOut:async()=>{}
};
export const notifications={subscribe:async()=>({ok:true})};
export const ws={connect:()=>{const connectionId=crypto.randomUUID();const handlers=new Set<(m:any)=>void>();return {connectionId,ready:Promise.resolve(),onMessage:(h:(m:any)=>void)=>{handlers.add(h);return()=>handlers.delete(h)},disconnect:()=>handlers.clear()}}};
export const image={resizeIfNeeded:async(file:File,opts:any={})=>{const data=await new Promise<string>((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(String(r.result||'').split(',')[1]||'');r.onerror=()=>reject(r.error);r.readAsDataURL(file)});return {data,mimeType:file.type||opts.mimeType||'application/octet-stream'};}};
