#!/usr/bin/env python

import simplejson

#bills/old/IL/2003/HB5102/HB5102_Introduced.txt^^^

'''
{"namespace": "bills.avro",
 "type": "record",
 "name": "Bill",
 "fields": [
     {"name": "year", "type": "int"},
     {"name": "state",  "type": "string"},
     {"name": "docid", "type": "string"},
     {"name": "docversion", "type": "string"},
     {"name": "content", "type": "string"},
     {"name": "primary_key", "type": "string"}
 ]
}
'''

output_dicts = list() # = {'year':None, 'state':'', 'docid':'', 'docversion':'', 'content':''}

with open("../data/partition_99",mode="r") as finput:
    lines = finput.readlines()
    for line in lines:
        sid, content = line.split("^^^")
        blah, bla, state, year, docid, docversion_pre = sid.split("/")
        output_dict = {'year':None, 'state':'', 'docid':'', 'docversion':'', 'content':None}
        output_dict['year'] = int(year)
        output_dict['state'] = state
        output_dict['docid'] = docid
        version = docversion_pre.split("_")[1].rstrip(".txt")
        output_dict['docversion'] = version
        output_dict['content'] = content.decode("utf-8",errors='replace')
        output_dict['primary_key'] = state+"_"+year+"_"+docid+"_"+version
        output_dicts.append(output_dict)


with open('../data/bills.json', 'w') as fp:
    simplejson.dump(output_dicts, fp)
