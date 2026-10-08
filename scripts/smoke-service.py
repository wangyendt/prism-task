#!/usr/bin/env python3
"""Explicit live metadata smoke check. Does not create an inference job."""
import json, os, urllib.request, urllib.error, sys
base=os.environ.get('BASE_URL','').rstrip('/')
token=os.environ.get('TOKEN','')
if not base.startswith('https://') or not token:
    sys.exit('Set HTTPS BASE_URL (including proxy prefix) and TOKEN in the environment.')
request=urllib.request.Request(base+'/v1/models',headers={'Authorization':'Bearer '+token,'Accept':'application/json'})
try:
    opener=urllib.request.build_opener(type('NoRedirect',(urllib.request.HTTPRedirectHandler,),{'redirect_request':lambda *args:None})())
    with opener.open(request,timeout=45) as response: catalog=json.load(response)
except urllib.error.HTTPError as error: sys.exit('Catalog request failed: HTTP '+str(error.code))
except Exception: sys.exit('Catalog connection or format failed; details suppressed.')
models=catalog.get('models')
if not isinstance(models,list) or not models: sys.exit('Invalid or empty catalog.')
for model in models:
    if not isinstance(model.get('id'),str) or not isinstance(model.get('reasoningLevels'),list): sys.exit('Malformed model entry.')
print(json.dumps({'source':catalog.get('source'),'stale':catalog.get('stale'),'count':len(models),'models':[{'id':m['id'],'reasoningLevels':m['reasoningLevels']} for m in models]},ensure_ascii=False,indent=2))
