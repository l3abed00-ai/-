package com.example.python

import android.content.Context
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

object PythonToolkit {

    fun planToJsonString(plan: JadhadhaPlan): String {
        val root = JSONObject()
        root.put("topic", plan.topic)
        root.put("component", plan.componentType.id)

        val info = JSONObject().apply {
            put("level", plan.info.level)
            put("unit", plan.info.unit)
            put("domain", plan.info.domain)
            put("component", plan.info.component)
            put("duration", plan.info.duration)
            put("materials", plan.info.materials)

            val comps = JSONObject().apply {
                put("communicative", plan.info.communicativeComp)
                put("methodological", plan.info.methodologicalComp)
                put("cultural", plan.info.culturalComp)
            }
            put("comps", comps)
        }
        root.put("info", info)

        val stages = JSONObject()
        plan.stages.forEach { (key, data) ->
            val stageObj = JSONObject().apply {
                put("eval", JSONArray(data.eval))
                put("content", JSONArray(data.content))
                put("teacher", JSONArray(data.teacher))
                put("abil", JSONArray(data.abil))
            }
            stages.put(key, stageObj)
        }
        root.put("stages", stages)

        return root.toString(2)
    }

    fun exportJsonFile(context: Context, plan: JadhadhaPlan): File {
        val safeTitle = plan.topic.replace(Regex("[^\\w\\p{L}]+"), "_").take(40).ifEmpty { "plan" }
        val outDir = File(context.cacheDir, "python_exports").apply { mkdirs() }
        val file = File(outDir, "${safeTitle}.json")
        file.writeText(planToJsonString(plan), StandardCharsets.UTF_8)
        return file
    }

    fun exportPythonScript(context: Context, component: PedagogicalComponent = PedagogicalComponent.READING): File {
        val outDir = File(context.cacheDir, "python_exports").apply { mkdirs() }
        val (fileName, scriptContent) = when (component) {
            PedagogicalComponent.READING -> "jadhadha_build.py" to GET_READING_PYTHON_SCRIPT()
            PedagogicalComponent.LANGUAGE -> "build_jadhadha.py" to GET_LANGUAGE_PYTHON_SCRIPT()
            PedagogicalComponent.EXPRESSION -> "build_insha.py" to GET_EXPRESSION_PYTHON_SCRIPT()
        }
        val file = File(outDir, fileName)
        file.writeText(scriptContent, StandardCharsets.UTF_8)
        return file
    }

    fun getScriptForComponent(component: PedagogicalComponent): String = when (component) {
        PedagogicalComponent.READING -> GET_READING_PYTHON_SCRIPT()
        PedagogicalComponent.LANGUAGE -> GET_LANGUAGE_PYTHON_SCRIPT()
        PedagogicalComponent.EXPRESSION -> GET_EXPRESSION_PYTHON_SCRIPT()
    }

    fun GET_PYTHON_SCRIPT(): String = GET_READING_PYTHON_SCRIPT()

    fun GET_READING_PYTHON_SCRIPT(): String = """#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# jadhadha_build.py — مولد جذاذات النصوص القرائية (python-docx)
import sys, os, json, argparse
from pathlib import Path

try:
    from docx import Document
    from docx.shared import Inches, Pt
except ImportError:
    sys.exit("المكتبة python-docx غير مثبتة. نفذ الأمر: pip install python-docx")

def build_from_json(json_path, output_dir="الجذاذات_المنجزة"):
    p = Path(json_path)
    if not p.is_file():
        print(f"الملف غير موجود: {json_path}")
        return
    with open(p, "r", encoding="utf-8") as f:
        data = json.load(f)
    topic = data.get("topic", "درس قرائي")
    print(f"جارٍ بناء جذاذة النصوص القرائية «{topic}» من ملف JSON...")
    doc = Document()
    for s in doc.sections:
        s.top_margin = Inches(0.5)
        s.bottom_margin = Inches(0.5)
        s.left_margin = Inches(0.5)
        s.right_margin = Inches(0.5)
    
    title_p = doc.add_paragraph()
    title_p.paragraph_format.alignment = 1
    run = title_p.add_run(f"الجذاذة النموذجية للنصوص القرائية: {topic}")
    run.font.name = "Sakkal Majalla"
    run.font.size = Pt(16)
    run.bold = True
    
    out_dir = Path(output_dir) / "النصوص القرائية"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"جذاذة_{topic}.docx"
    doc.save(str(out_file))
    print(f"✔ تم حفظ الجذاذة بنجاح: {out_file}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="مولد جذاذات القراءة")
    parser.add_argument("--from-json", help="مسار ملف JSON للجذاذة")
    parser.add_argument("--out", default="الجذاذات_المنجزة", help="مجلد الإخراج")
    args = parser.parse_args()
    if args.from_json:
        build_from_json(args.from_json, args.out)
    else:
        print("استخدم: python jadhadha_build.py --from-json <ملف.json>")
"""

    fun GET_LANGUAGE_PYTHON_SCRIPT(): String = """#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# build_jadhadha.py — جذاذة الدرس اللغوي (python-docx)
import sys, os, json, argparse
from pathlib import Path

try:
    from docx import Document
    from docx.shared import Inches, Pt
except ImportError:
    sys.exit("المكتبة python-docx غير مثبتة. نفذ الأمر: pip install python-docx")

def build_language_plan(json_path, output_dir="الجذاذات_المنجزة"):
    p = Path(json_path)
    if not p.is_file():
        print(f"الملف غير موجود: {json_path}")
        return
    with open(p, "r", encoding="utf-8") as f:
        data = json.load(f)
    topic = data.get("topic", "الدرس اللغوي")
    print(f"جارٍ بناء جذاذة الدرس اللغوي «{topic}»...")
    doc = Document()
    for s in doc.sections:
        s.top_margin = Inches(0.5)
        s.bottom_margin = Inches(0.5)
        s.left_margin = Inches(0.5)
        s.right_margin = Inches(0.5)
    
    title_p = doc.add_paragraph()
    title_p.paragraph_format.alignment = 1
    run = title_p.add_run(f"الجذاذة النموذجية للدرس اللغوي: {topic}")
    run.font.name = "Sakkal Majalla"
    run.font.size = Pt(16)
    run.bold = True
    
    out_dir = Path(output_dir) / "الدرس اللغوي"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"جذاذة_{topic}.docx"
    doc.save(str(out_file))
    print(f"✔ تم حفظ جذاذة الدرس اللغوي بنجاح: {out_file}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="جذاذة الدرس اللغوي")
    parser.add_argument("--from-json", help="مسار ملف JSON")
    parser.add_argument("--out", default="الجذاذات_المنجزة", help="مجلد الإخراج")
    args = parser.parse_args()
    if args.from_json:
        build_language_plan(args.from_json, args.out)
    else:
        print("استخدم: python build_jadhadha.py --from-json <ملف.json>")
"""

    fun GET_EXPRESSION_PYTHON_SCRIPT(): String = """#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# build_insha.py — جذاذة التعبير والإنشاء (الحصص الأربع)
import sys, os, json, argparse
from pathlib import Path

try:
    from docx import Document
    from docx.shared import Inches, Pt
except ImportError:
    sys.exit("المكتبة python-docx غير مثبتة. نفذ الأمر: pip install python-docx")

def build_insha_plan(json_path, output_dir="الجذاذات_المنجزة"):
    p = Path(json_path)
    if not p.is_file():
        print(f"الملف غير موجود: {json_path}")
        return
    with open(p, "r", encoding="utf-8") as f:
        data = json.load(f)
    topic = data.get("topic", "مهارة إنشائية")
    print(f"جارٍ بناء جذاذة التعبير والإنشاء (الحصص الأربع) «{topic}»...")
    doc = Document()
    for s in doc.sections:
        s.top_margin = Inches(0.5)
        s.bottom_margin = Inches(0.5)
        s.left_margin = Inches(0.5)
        s.right_margin = Inches(0.5)
    
    title_p = doc.add_paragraph()
    title_p.paragraph_format.alignment = 1
    run = title_p.add_run(f"الجذاذة النموذجية للتعبير والإنشاء: {topic}")
    run.font.name = "Sakkal Majalla"
    run.font.size = Pt(16)
    run.bold = True
    
    out_dir = Path(output_dir) / "التعبير والإنشاء"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"جذاذة_{topic}.docx"
    doc.save(str(out_file))
    print(f"✔ تم حفظ جذاذة التعبير والإنشاء بنجاح: {out_file}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="جذاذة التعبير والإنشاء")
    parser.add_argument("--from-json", help="مسار ملف JSON")
    parser.add_argument("--out", default="الجذاذات_المنجزة", help="مجلد الإخراج")
    args = parser.parse_args()
    if args.from_json:
        build_insha_plan(args.from_json, args.out)
    else:
        print("استخدم: python build_insha.py --from-json <ملف.json>")
"""
}
