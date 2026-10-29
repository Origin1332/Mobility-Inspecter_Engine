"""Extract source-grounded rows from the supplied 2023 parts catalogue layout.

Only listed engine groups are indexed. Options/dates/remarks stay separate.
Returned rows are candidate catalogue references, never an automatic fit decision.
"""
import hashlib
import json
import re
from pathlib import Path

# PDF group + main callouts + related callouts. None means all other group callouts.
# Fuse-box interior assembly [10] and its dedicated fuses are deliberately excluded.
BINDINGS = {
    'battery': [('2610', ['01'], None)],
    'fuse_box': [('8410', ['01','02','03'], ['11','12','13','16','17'])],
    'air_cleaner': [('2313', ['01'], None)],
    'coolant_reservoir': [('2130', ['12'], ['02','06','07','08','09','10','11','13','14','15','16','17','18','19'])],
    'fuel_filter': [('2220', ['01'], None)],
    'oil_filler_cap': [('1225', ['04'], [])],
    'oil_dipstick': [('1536', ['32'], ['31','33','34'])],
    'alternator': [('1451', ['01'], None), ('1453', [], None)],
    'ac_compressor': [('1480', ['01'], None)],
    'belt_tensioner': [('1336', ['04','29'], ['05','07','08','25','28']), ('1350', [], None)],
    'egr_valve': [('1793', ['01'], None)],
    'washer_fluid_reservoir': [('7820', ['01'], None)],
}

def relations(group, callout):
    result={}
    for cls, bindings in BINDINGS.items():
        for code,primary,related in bindings:
            if code==group and (callout in primary or related is None or callout in related):
                result[cls]='primary' if callout in primary else 'related'
    return result

def parse_page(page, page_number):
    # Use the span origin for columns: long names overflow the cell border in this PDF.
    spans=[]; seen=set()
    for block in page.get_text('dict')['blocks']:
        for line in block.get('lines',[]):
            for span in line.get('spans',[]):
                text=span['text'].strip()
                x,y=span['bbox'][:2]
                key=(round(x,2),round(y,2),text)
                if text and key not in seen:
                    seen.add(key); spans.append((x,y,text,span['bbox']))
    header=sorted([s for s in spans if s[1]<70],key=lambda s:(s[1],s[0]))
    code=next((s[2] for s in header if re.fullmatch(r'\d{4}',s[2])),None)
    if code is None: return None,[],None
    title=' / '.join(s[2] for s in header if s[2]!=code)
    anchors=sorted([s for s in spans if 60<=s[0]<130 and 140<s[1]<755 and re.fullmatch(r'\[\d+\]',s[2])],key=lambda s:s[1])
    rows=[]
    for i,a in enumerate(anchors):
        bottom=anchors[i+1][1]-1 if i+1<len(anchors) else 755
        values=sorted([s for s in spans if a[1]-1<=s[1]<bottom],key=lambda s:(s[1],s[0]))
        number=next((s[2] for s in values if 60<=s[0]<130 and re.fullmatch(r'[A-Z0-9]{4,20}',s[2]) and s[2]!=a[2]),None)
        if not number: raise ValueError(f'Cannot parse part number on PDF page {page_number}')
        names=[s[2] for s in values if 130<=s[0]<250 and s[1]<a[1]+19]
        options=[s[2] for s in values if 130<=s[0]<250 and s[1]>=a[1]+19]
        def column(lo,hi): return ' '.join(s[2] for s in values if lo<=s[0]<hi)
        row={'id':f'p{page_number}-r{i}-{number}','group_code':code,'group_title':title,
             'callout':a[2][1:-1],'part_number':number,'name_ko':names[0] if names else '',
             'name_en':' '.join(names[1:]),'options':' '.join(options),
             'side':column(250,265),'transmission':column(265,280),
             'model_quantities':column(280,395),
             'from_date':column(395,425),'to_date':column(425,460),'remarks':column(460,590),
             'pdf_page':page_number,'printed_page':column(1000,1001),
             'row_rect':[45,round(a[1]-3,2),552,round(bottom,2)],
             'page_width':float(page.rect.width),'page_height':float(page.rect.height)}
        footer=next((s[2] for s in spans if s[0]>=520 and s[1]>790 and s[2].isdigit()),'')
        row['printed_page']=footer
        row['relations']=relations(code,row['callout'])
        if not row['name_ko']: raise ValueError(f'Missing part name on page {page_number}')
        rows.append(row)
    return code,rows,{'title':title,'page':page_number,'diagram':not anchors}

def build_index(pdf_path):
    import pymupdf
    source=Path(pdf_path)
    wanted={b[0] for v in BINDINGS.values() for b in v}
    rows=[]; groups={}
    with pymupdf.open(source) as pdf:
        for i,p in enumerate(pdf):
            # Ignore unrelated pages before layout parsing.
            first=p.get_text().splitlines()[:1]
            if not first or first[0].strip() not in wanted: continue
            code,parsed,meta=parse_page(p,i+1)
            if code not in wanted: continue
            g=groups.setdefault(code,{'title':meta['title'],'pages':[],'diagram_pages':[]})
            g['pages'].append(i+1)
            if meta['diagram']: g['diagram_pages'].append(i+1)
            rows.extend(r for r in parsed if r['relations'])
        pages=len(pdf)
    missing=[c for c in BINDINGS if not any(r['relations'].get(c)=='primary' for r in rows)]
    if missing: raise ValueError(f'Catalogue layout/groups not recognized for {missing}')
    for row in rows: row['diagram_pages']=groups[row['group_code']]['diagram_pages']
    return {'schema_version':1,'title':'2023 렉스턴스포츠 부품 카다로그',
            'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
            'pdf_file':'catalog.pdf','page_count':pages,'groups':groups,'rows':rows,
            'fitment_status':'requires_vehicle_configuration_check',
            'notice':'품번 후보입니다. 생산일·엔진·ISG·변속기·차대번호에 따른 적용 여부는 원문과 실제 차량으로 확인하세요.'}

def search(index, class_name=None, query=''):
    query=query.casefold().replace(' ','').replace('-','')
    def matches(row):
        if class_name and class_name not in row['relations']: return False
        text=' '.join(str(row[k]) for k in ('part_number','name_ko','name_en','options','remarks','group_code'))
        return query in text.casefold().replace(' ','').replace('-','')
    return sorted(filter(matches,index['rows']),key=lambda r:(
        r['relations'].get(class_name)!='primary',r['pdf_page'],r['row_rect'][1]))
