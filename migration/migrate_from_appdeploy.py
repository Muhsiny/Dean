#!/usr/bin/env python3
import os,json,urllib.request,urllib.parse,concurrent.futures,hashlib
import boto3

EXPORT_BASE=os.environ['APPDEPLOY_EXPORT_BASE']
EXPORT_TOKEN=os.environ['APPDEPLOY_EXPORT_TOKEN']
TARGET=os.environ['TARGET_IMPORT_URL']
MIGRATION_TOKEN=os.environ['MIGRATION_TOKEN']
S3_ENDPOINT=os.environ['S3_ENDPOINT']
S3_ACCESS_KEY=os.environ['S3_ACCESS_KEY']
S3_SECRET_KEY=os.environ['S3_SECRET_KEY']
S3_BUCKET=os.environ.get('S3_BUCKET','sayeh-public')
S3_REGION=os.environ.get('S3_REGION','eu-central-1')
TABLES=['news','news_delete_tombstones','settings','socials','sources','staff_members','system_migrations','visitor_events_v3','news_index_v1','analysis_desk_runs_v1','analysis_drafts_v1','analysis_desk_state_v1','news_translations_v1','video_bulletins_v1','entity_subscriptions']

def get_json(params):
    q=urllib.parse.urlencode(params)
    with urllib.request.urlopen(EXPORT_BASE+'?'+q,timeout=90) as r:
        return json.loads(r.read())

def post_json(url,data,headers=None):
    h={'content-type':'application/json'}
    if headers:h.update(headers)
    req=urllib.request.Request(url,data=json.dumps(data,ensure_ascii=False).encode(),headers=h,method='POST')
    with urllib.request.urlopen(req,timeout=120) as r:return json.loads(r.read())

def pages_for_table(table):
    token=''
    while True:
        p={'token':EXPORT_TOKEN,'kind':'table','table':table,'limit':'100'}
        if token:p['next']=token
        data=get_json(p); yield data.get('items',[])
        token=data.get('nextToken') or ''
        if not token:break

def import_table(table):
    total=0
    for rows in pages_for_table(table):
        if not rows:continue
        if table=='news':
            payload={'source':'appdeploy-'+table,'articles':rows}
        elif table=='settings':
            payload={'source':'appdeploy-'+table,'settings':rows[0] if rows else {}}
        else:
            payload={'source':'appdeploy-'+table,'entities':{table:rows}}
        result=post_json(TARGET, payload, {'x-migration-token':MIGRATION_TOKEN})
        total+=len(rows)
    print('TABLE',table,total,flush=True); return total

s3=boto3.client('s3',endpoint_url=S3_ENDPOINT,region_name=S3_REGION,aws_access_key_id=S3_ACCESS_KEY,aws_secret_access_key=S3_SECRET_KEY)
def copy_object(item):
    path=item.get('path') or ''
    url=item.get('url') or ''
    if not path or not url:return (path,False,'missing')
    try:
        with urllib.request.urlopen(url,timeout=120) as r:
            data=r.read(); ctype=r.headers.get('content-type') or 'application/octet-stream'
        s3.put_object(Bucket=S3_BUCKET,Key=path,Body=data,ContentType=ctype)
        return (path,True,hashlib.sha256(data).hexdigest())
    except Exception as e:return (path,False,str(e))

def import_storage():
    token=''; total=ok=0; failed=[]
    while True:
        p={'token':EXPORT_TOKEN,'kind':'storage','limit':'200'}
        if token:p['next']=token
        data=get_json(p); items=data.get('items',[]); total+=len(items)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
            for path,good,detail in ex.map(copy_object,items):
                if good:ok+=1
                else:failed.append((path,detail))
        print('STORAGE PAGE',len(items),'ok',ok,'total',total,flush=True)
        token=data.get('nextToken') or ''
        if not token:break
    print('STORAGE DONE',total,ok,'failed',len(failed),flush=True)
    if failed:print(json.dumps(failed[:30],ensure_ascii=False))
    return {'total':total,'ok':ok,'failed':len(failed)}

def main():
    counts={t:import_table(t) for t in TABLES}
    media=import_storage()
    print(json.dumps({'tables':counts,'storage':media},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
