package com.example.python

import android.content.Context
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StructuredTable
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
            put("reference", plan.info.reference)
            put("teacherName", plan.info.teacherName)
            put("schoolName", plan.info.schoolName)

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
                put("learner", JSONArray(data.learner))
                put("abil", JSONArray(data.abil))
                if (data.effectiveTables().isNotEmpty()) {
                    put("tables", structuredTablesToJson(data.effectiveTables()))
                }
            }
            stages.put(key, stageObj)
        }
        root.put("stages", stages)
        if (plan.customTables.isNotEmpty()) {
            root.put("customTables", structuredTablesToJson(plan.customTables))
        }
        if (plan.extraParagraphs.isNotEmpty()) {
            root.put("extraParagraphs", JSONArray(plan.extraParagraphs))
        }
        if (plan.unclassifiedItems.isNotEmpty()) {
            root.put("unclassifiedItems", JSONArray(plan.unclassifiedItems))
        }

        return root.toString(2)
    }

    private fun structuredTablesToJson(tables: List<StructuredTable>): JSONArray {
        val arr = JSONArray()
        for (tbl in tables) {
            val tblObj = JSONObject()
            tblObj.put("title", tbl.title)
            tblObj.put("isRtl", tbl.isRtl)
            val rowsArr = JSONArray()
            for (row in tbl.rows) {
                val rowObj = JSONObject()
                rowObj.put("isHeader", row.isHeader)
                val cellsArr = JSONArray()
                for (cell in row.cells) {
                    val cellObj = JSONObject()
                    cellObj.put("text", cell.text)
                    cellObj.put("paragraphs", JSONArray(cell.paragraphs))
                    cellObj.put("gridSpan", cell.gridSpan)
                    if (cell.vMerge != null) cellObj.put("vMerge", cell.vMerge)
                    if (cell.nestedTables.isNotEmpty()) {
                        cellObj.put("nestedTables", structuredTablesToJson(cell.nestedTables))
                    }
                    cellsArr.put(cellObj)
                }
                rowObj.put("cells", cellsArr)
                rowsArr.put(rowObj)
            }
            tblObj.put("rows", rowsArr)
            arr.put(tblObj)
        }
        return arr
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

    private val COMMON_PYTHON_DOCX_BUILDER_HELPERS = """
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

def set_table_rtl(table):
    tblPr = table._tbl.tblPr
    bidi = OxmlElement('w:bidiVisual')
    tblPr.append(bidi)

def format_cell_text(cell, lines, font_name="Sakkal Majalla", size_pt=10, bold=True):
    cell.text = ""
    if not lines:
        lines = [""]
    for idx, line in enumerate(lines):
        p = cell.paragraphs[0] if idx == 0 else cell.add_paragraph()
        p.paragraph_format.alignment = 1
        pPr = p._p.get_or_add_pPr()
        pPr.append(OxmlElement('w:bidi'))
        run = p.add_run(str(line))
        run.font.name = font_name
        run.font.size = Pt(size_pt)
        run.bold = bold
        rPr = run._r.get_or_add_rPr()
        rPr.append(OxmlElement('w:rtl'))

def append_header_table(doc, data, component_override=None):
    info = data.get("info", {})
    comps = info.get("comps", {})
    topic = data.get("topic", "")
    comp_title = component_override or info.get("component", "")
    tbl = doc.add_table(rows=7, cols=4)
    set_table_rtl(tbl)
    rows_spec = [
        ("الوحدة :", info.get("unit", ""), "الفئة المستهدفة :", info.get("level", "")),
        ("المجال :", info.get("domain", ""), "الوسائل التعليمية :", info.get("materials", "")),
        ("المكون :", comp_title, "الكفايات المستهدفة / الأهداف التعلمية:", ""),
        ("الموضوع :", topic, "- كفاية تواصلية: " + comps.get("communicative", ""), ""),
        ("مدة الإنجاز :", info.get("duration", ""), "- كفاية منهجية: " + comps.get("methodological", ""), ""),
        ("المرجع :", info.get("reference", ""), "- كفاية معرفية: " + comps.get("cultural", ""), ""),
        ("الأستاذ :", info.get("teacherName", ""), "المؤسسة :", info.get("schoolName", ""))
    ]
    for r_idx, (c0, c1, c2, c3) in enumerate(rows_spec):
        row_cells = tbl.rows[r_idx].cells
        format_cell_text(row_cells[0], [c0])
        format_cell_text(row_cells[1], [c1])
        if r_idx in (2, 3, 4, 5):
            row_cells[2].merge(row_cells[3])
            format_cell_text(row_cells[2], [c2])
        else:
            format_cell_text(row_cells[2], [c2])
            format_cell_text(row_cells[3], [c3])

def append_stages_table(doc, stages_dict, stage_keys):
    tbl = doc.add_table(rows=1, cols=5)
    set_table_rtl(tbl)
    headers = ["المراحل", "القدرات والمهارات", "الأنشطة التعليمية التعلمية", "المحتوى", "التقويم والدعم"]
    for idx, h in enumerate(headers):
        format_cell_text(tbl.rows[0].cells[idx], [h])
    for key in stage_keys:
        st = stages_dict.get(key, {})
        row_cells = tbl.add_row().cells
        format_cell_text(row_cells[0], [key])
        format_cell_text(row_cells[1], st.get("abil", []))
        activities = list(st.get("teacher", [])) + [x for x in st.get("learner", []) if x not in st.get("teacher", [])]
        format_cell_text(row_cells[2], activities)
        content_lines = [line for line in st.get("content", []) if not str(line).strip().startswith("[جدول:")]
        format_cell_text(row_cells[3], content_lines)
        for nested in st.get("tables", []):
            n_rows = nested.get("rows", [])
            if n_rows:
                max_cols = max(len(r.get("cells", [])) for r in n_rows)
                if max_cols > 0:
                    sub_tbl = row_cells[3].add_table(rows=len(n_rows), cols=max_cols)
                    set_table_rtl(sub_tbl)
                    for nr_i, nr in enumerate(n_rows):
                        for nc_i, nc in enumerate(nr.get("cells", [])):
                            if nc_i < max_cols:
                                format_cell_text(sub_tbl.rows[nr_i].cells[nc_i], nc.get("paragraphs", [nc.get("text", "")]), size_pt=9)
        format_cell_text(row_cells[4], st.get("eval", []))
"""

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
$COMMON_PYTHON_DOCX_BUILDER_HELPERS
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
        s.top_margin = Inches(0.28)
        s.bottom_margin = Inches(0.28)
        s.left_margin = Inches(0.28)
        s.right_margin = Inches(0.28)

    append_header_table(doc, data)
    doc.add_paragraph()
    stages = data.get("stages", {})
    ordered_keys = [k for k in ["diagnostic", "observation", "comprehension", "session2_intro", "analysis", "synthesis", "investment"] if k in stages]
    for k in stages.keys():
        if k not in ordered_keys:
            ordered_keys.append(k)
    append_stages_table(doc, stages, ordered_keys)

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
$COMMON_PYTHON_DOCX_BUILDER_HELPERS
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
        s.top_margin = Inches(0.28)
        s.bottom_margin = Inches(0.28)
        s.left_margin = Inches(0.28)
        s.right_margin = Inches(0.28)

    append_header_table(doc, data)
    doc.add_paragraph()
    stages = data.get("stages", {})
    ordered_keys = [k for k in ["lang_diagnostic", "lang_observation", "lang_analysis", "lang_synthesis", "lang_application"] if k in stages]
    for k in stages.keys():
        if k not in ordered_keys:
            ordered_keys.append(k)
    append_stages_table(doc, stages, ordered_keys)

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
$COMMON_PYTHON_DOCX_BUILDER_HELPERS
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
        s.top_margin = Inches(0.28)
        s.bottom_margin = Inches(0.28)
        s.left_margin = Inches(0.28)
        s.right_margin = Inches(0.28)

    stages = data.get("stages", {})
    append_header_table(doc, data)
    doc.add_paragraph()
    append_stages_table(doc, stages, list(stages.keys()))

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
