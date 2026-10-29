import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from contract import CLASSES
from dataset_io import load_dataset
from catalog_index import parse_page,relations,search

class FakePage:
    rect=type('Rect',(),{'width':595.0,'height':842.0})()
    def get_text(self,kind):
        spans=[]
        def add(x,y,text): spans.append({'text':text,'bbox':[x,y,x+50,y+8]})
        for x,y,t in [(50,29,'2610'),(100,29,'배터리 및 케이블'),(100,49,'BATTERY & CABLE'),
                      (85,150,'[01]'),(65,160,'268A036000'),(135,150,'배터리 어셈블리'),
                      (135,160,'BATTERY ASSY'),(135,171.7,'22M/Y,80AH-BATT,ISG'),
                      (280,155,'1'),(400,155,"21'1229"),(430,155,"'"),(460,155,'AS 공급 품번'),
                      (85,193,'[03]'),(65,203,'2686009000'),(135,193,'클램프-배터리'),
                      (135,203,'CLAMP-BATTERY'),(400,198,"18'0103"),(530,798,'130')]:
            add(x,y,t)
        return {'blocks':[{'lines':[{'spans':spans}]}]}

class CatalogTests(unittest.TestCase):
    def fixture(self,root,mutate=None):
        stream=io.BytesIO();Image.new('RGB',(100,80),'red').save(stream,format='JPEG')
        doc={'classes':list(CLASSES),'images':[{'file_name':'000001.jpg','width':100,'height':80,
             'objects':[{'class':'battery','bbox':[10,20,50,60]}]}]}
        if mutate:mutate(doc)
        path=root/'seed.zip'
        with zipfile.ZipFile(path,'w') as z:
            z.writestr('dataset/annotations.json',json.dumps(doc))
            z.writestr('dataset/images/000001.jpg',stream.getvalue())
        return path
    def test_sparse_seed_is_reviewable_not_trainable(self):
        with tempfile.TemporaryDirectory() as t:
            data,blobs=load_dataset(self.fixture(Path(t)))
            self.assertEqual(data['counts']['battery'],1)
            self.assertEqual(data['counts']['fuel_filter'],0)
            self.assertFalse(data['training_ready'])
            self.assertEqual(len(data['images'][0]['file_sha256']),64)
            self.assertIn('images/000001.jpg',blobs)
    def test_seed_rejects_wrong_class_order(self):
        with tempfile.TemporaryDirectory() as t:
            p=self.fixture(Path(t),lambda d:d['classes'].reverse())
            with self.assertRaises(ValueError):load_dataset(p)
    def test_seed_rejects_uncertain_bounds(self):
        with tempfile.TemporaryDirectory() as t:
            p=self.fixture(Path(t),lambda d:d['images'][0]['objects'][0].update(bbox=[10,20,101,60]))
            with self.assertRaises(ValueError):load_dataset(p)
    def test_seed_rejects_traversal(self):
        with tempfile.TemporaryDirectory() as t:
            p=self.fixture(Path(t),lambda d:d['images'][0].update(file_name='../outside.jpg'))
            with self.assertRaises(ValueError):load_dataset(p)
    def test_catalog_columns_keep_options_and_dates_separate(self):
        group,rows,meta=parse_page(FakePage(),132)
        self.assertEqual(group,'2610');self.assertFalse(meta['diagram'])
        self.assertEqual(len(rows),2)
        row=rows[0]
        self.assertEqual(row['part_number'],'268A036000')
        self.assertEqual(row['options'],'22M/Y,80AH-BATT,ISG')
        self.assertEqual(row['from_date'],"21'1229")
        self.assertEqual(row['remarks'],'AS 공급 품번')
        self.assertEqual(row['printed_page'],'130')
        self.assertEqual(row['relations'],{'battery':'primary'})
        self.assertEqual(rows[1]['relations'],{'battery':'related'})
    def test_search_exact_number_and_korean_alias_spacing(self):
        _,rows,_=parse_page(FakePage(),132)
        idx={'rows':rows}
        self.assertEqual(search(idx,'battery','268-A036000')[0]['part_number'],'268A036000')
        self.assertEqual(search(idx,'battery','클램프')[0]['part_number'],'2686009000')
        self.assertEqual(search(idx,'air_cleaner',''),[])
    def test_interior_fuse_box_is_not_engine_box(self):
        self.assertEqual(relations('8410','10'),{})
        self.assertEqual(relations('8410','13'),{'fuse_box':'related'})
    def test_belt_tensioner_uses_actual_timing_case_group(self):
        self.assertEqual(relations('1336','04'),{'belt_tensioner':'primary'})
        self.assertEqual(relations('1350','01'),{'belt_tensioner':'related'})

if __name__=='__main__':unittest.main()
