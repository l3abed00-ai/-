# إعادة بناء قراءة ملفات Word (.docx) عبر XmlPullParser في DocumentFileReader

تهدف هذه الخطة إلى استبدال محلّل التعابير النمطية (Regex) في `DocumentFileReader` بمحلّل XML متدفق قياسي مبني على `XmlPullParser` (`android.util.Xml`) مع نموذج بيانات وسيط (`Para` و`Table`) ومكدّس عمق (Depth Stack)، لمعالجة الجداول المتداخلة، وفواصل الأسطر (`Shift+Enter`)، ومربعات النص (`AlternateContent`)، والتعديلات المراجعة (`del`/`ins`)، وملفات الرأس والتذييل (`header*.xml` و`footer*.xml`)، مع الاحتفاظ بالمحلّل القديم كمسار احتياطي آمن (`parseDocumentXmlLegacy`) عند مواجهة XML تالف، ودون أي تعديل على `JadhadhaParser` أو صيغة النص الناتج.

## User Review & Critical Decisions

> [!IMPORTANT]
> تم اعتماد اختياراتك المؤكدة من أسئلة التوضيح وتضمينها في تصميم المحلّل الجديد:

- **قرار مؤكد ١ (تمثيل الجداول المتداخلة داخل الخلايا)**: عند وجود جدول متداخل (`<w:tbl>`) داخل خلية (`<w:tc>`)، تُستخرج فقرات خلاياه بترتيبها وتُضاف مباشرةً إلى قائمة فقرات الخلية الأم (`TableCell.paragraphs`) دون بتر أو تكرار.
- **قرار مؤكد ٢ (تحليل الجداول والفقرات في الرأس والتذييل)**: تُمرّر ملفات `word/header*.xml` و`word/footer*.xml` عبر نفس محلّل الوثيقة (`parseDocumentXml`) بحيث تُستخرج الجداول الموجودة في الترويسة (بصيغة `«عنوان: عنصر - عنصر»`) والفقرات النصية معًا، وتُلحق أسطرها غير المكررة في **آخر الناتج** حتى لا تؤثر على كشف المكون في أول 25 سطرًا.

---

## 1. Overview & Core Concept

- **الوظيفة الأساسية**: تحويل بنية XML الداخلية لملفات `.docx` (`word/document.xml` و`word/header*.xml` و`word/footer*.xml`) إلى تمثيل وسيط مهيكل (`Para` و`Table`) في مرور متدفق واحد عبر `XmlPullParser` مع تفعيل الفضاءات الاسمية (`FEATURE_PROCESS_NAMESPACES`)، ثم توليد نفس الناتج النصي المعتمد الذي يتوقعه `JadhadhaParser`.
- **الفئة المستهدفة**: أساتذة اللغة العربية الذين يرفعون جذاذات `.docx` متنوعة التنسيق (تتضمن جداول فرعية، فواصل أسطر يدوية `Shift+Enter`، مربعات نص، ترويسات رسمية في الرأس والتذييل، أو تتبع تغييرات Word).
- **القيمة المضافة**:
  1. القضاء على التصاق الكلمات الناتج عن تجاهل فواصل الأسطر (`br`/`cr`) وعلامات الجدولة (`tab`).
  2. منع تكرار النصوص الموجودة داخل مربعات النص (`AlternateContent` ← قراءة `Choice` وتجاهل `Fallback`).
  3. استيعاب الجداول المتداخلة بسلاسة داخل خلاياها الأم دون ضياع أو بتر لبقية صفوف الجدول الرئيسي.
  4. استخراج بيانات الترويسة والتذييل وإلحاقها في نهاية النص دون التشويش على خوارزمية `detectComponent`.
  5. صمود تام أمام ملفات XML غير القياسية أو التالفة بفضل التراجع التلقائي (`try/catch`) إلى `parseDocumentXmlLegacy`.

---

## 2. User Experience & Visual Design

- **شفافية التحسين وسلاسة الأداء**:
  - يعمل المحلّل الجديد خلف الكواليس ضمن نفس واجهة شاشة التفريغ (`ExtractorScreen`) وعلى خيط `Dispatchers.IO` مع احترام حدود الحجم (25 ميغابايت للملف المرفوع و20 ميغابايت لمجموع البايتات المفكوكة من الأرشيف).
  - يظهر النص المستخرج في حقل المعاينة وشاشة «مراجعة التفريغ» خاليًا من التصاق الكلمات أو تكرار مربعات النص، مع ظهور معلومات الرأس والتذييل في أسفل النص المستخرج.
- **الحفاظ على الهوية البصرية**:
  - لا يتغير أي عنصر في تخطيط الواجهة (RTL) أو شارات المصادر (`FILE` / `DEFAULT` / `AI` / `USER`) أو معاينة وتصدير ملفات Word.

---

## 3. Key Product Decisions & Trade-Offs

- **القرار ١: استخدام `XmlPullParser` مع `FEATURE_PROCESS_NAMESPACES = true` ومطابقة الاسم المحلي (`localName`)**
  - *النهج المختار*: تهيئة `Xml.newPullParser()` بتفعيل `FEATURE_PROCESS_NAMESPACES` والاعتماد على `parser.name` (مثل `"tbl"`, `"tr"`, `"tc"`, `"p"`, `"r"`, `"t"`, `"br"`, `"cr"`, `"tab"`, `"noBreakHyphen"`, `"del"`, `"delText"`, `"ins"`, `"AlternateContent"`, `"Choice"`, `"Fallback"`, `"u"`, `"gridSpan"`, `"vMerge"`) بغضّ النظر عن بادئة الفضاء الاسمي (`w:` أو `mc:` أو بدون بادئة).
  - *السبب*: ملفات `.docx` المُصدّرة من برامج مختلفة (Microsoft Word، LibreOffice، WPS، Google Docs) قد تستخدم بادئات فضاءات اسمية مختلفة أو تعرّف فضاءات افتراضية؛ الاعتماد على الاسم المحلي يتعامل معها جميعًا بدقة، كما يتولى المحلّل فك رموز الكيانات (`&amp;`, `&lt;`, ...) تلقائيًا دون الحاجة إلى `decodeXmlEntities`.

- **القرار ٢: دمج فقرات الجداول المتداخلة في الخلية الأم عبر مكدّس العمق (Depth Stack)**
  - *النهج المختار*: أثناء البناء المتدفق، يُحفظ مكدّس للجداول المفتوحة (`ArrayDeque<TableBuilder>`) ومكدّس للخلايا المفتوحة (`ArrayDeque<CellBuilder>`). عندما يُغلق جدول متداخل (`tableStack.size > 1`)، تُجمع فقرات خلاياه بالترتيب وتُضاف مباشرة إلى قائمة فقرات الخلية الأم النشطة (`cellStack.last().paragraphs`).
  - *السبب*: يضمن عدم اختلاط صفوف الجدول الفرعي (`tr`/`tc`) بصفوف الجدول الأب، ويحافظ على تسلسل القراءة الطبيعي داخل الخلية الأم.

- **القرار ٣: إلحاق نصوص `header*.xml` و`footer*.xml` في نهاية الناتج مع إزالة التكرار**
  - *النهج المختار*: قراءة جميع مدخلات الـ ZIP في تمريرة واحدة دون توقف مبكر عند `word/document.xml`، وتحليل كل ملف يطابق `word/header*.xml` أو `word/footer*.xml` بنفس دالة `parseDocumentXml`، ثم إلحاق الأسطر غير المكررة في نهاية النص بعد أسطر `word/document.xml`.
  - *السبب*: دالة `detectComponent` في `JadhadhaParser` تفحص أول 25 سطرًا لتحديد المكون؛ وضع الرأس والتذييل في النهاية يمنع الكلمات العامة في الترويسة من التأثير على دقة اكتشاف المكون مع ضمان عدم ضياع أي معلومة موجودة في الرأس.

- **القرار ٤: الأمان عبر `parseDocumentXmlLegacy`**
  - *النهج المختار*: الاحتفاظ بكامل كود المحلّل السابق تحت اسم `parseDocumentXmlLegacy(xml: String): String` واستدعائه داخل كتلة `catch` في حال فشل `XmlPullParser` بسبب XML تالف أو غير مكتمل.
  - *السبب*: ضمان عدم انهيار التطبيق في أي حالة، واجتياز معيار القبول السابع (XML تالف ← لا انهيار، ويعمل المسار القديم).

---

## 4. Technical Architecture & Data Strategy

### مخطط المعمارية وتدفق البيانات (Architecture & Data Flow)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                  extractTextFromDocxStreamInternal (ZIP Stream)              │
│  • يفحص جميع المدخلات مع مراقبة الحد الأقصى (20 MB Uncompressed)            │
│  • يحفظ محتوى:                                                               │
│    1) word/document.xml                                                      │
│    2) word/header*.xml & word/footer*.xml (بأي ترتيب في الأرشيف)             │
└───────────────────┬──────────────────────────────────────┬───────────────────┘
                    │                                      │
                    ▼                                      ▼
┌───────────────────────────────────────────┐  ┌───────────────────────────────┐
│      parseDocumentXml(documentXml)        │  │  parseDocumentXml(headerXml)  │
│  ┌─────────────────────────────────────┐  │  │  • يحلل الجداول والفقرات      │
│  │ try: parseDocumentXmlPull(xml)      │  │  │  • يستخرج الأسطر غير المكررة  │
│  │  1. XmlPullParser (NAMESPACES=true) │  │  └───────────────┬───────────────┘
│  │  2. Depth Stack Builder:            │  │                  │
│  │     • AlternateContent -> Choice    │  │                  │
│  │     • Ignore <del> & <delText>      │  │                  │
│  │     • Keep <ins>                    │  │                  │
│  │     • tab -> " ", noBreakHyphen->"-"│  │                  │
│  │     • br / cr -> split Para         │  │                  │
│  │     • <u val!="none"> + ":" -> <u>  │  │                  │
│  │     • Nested Table -> parent Cell   │  │                  │
│  │  3. Emit Document Elements:         │  │                  │
│  │     • Para(text) -> plain lines     │  │                  │
│  │     • Table -> parseTableXml(table) │  │                  │
│  ├─────────────────────────────────────┤  │                  │
│  │ catch (Exception):                  │  │                  │
│  │     parseDocumentXmlLegacy(xml)     │  │                  │
│  └─────────────────┬───────────────────┘  │                  │
└────────────────────┼──────────────────────┘                  │
                     │                                         │
                     ▼                                         ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│           دمج الناتج النهائي: أسطر المتن + أسطر الرأس والتذييل الفريدة        │
│   (يُسلّم إلى JadhadhaParser بنفس الصيغة المعتمدة دون أي تغيير في المحلّل)     │
└──────────────────────────────────────────────────────────────────────────────┘
```

### النموذج الوسيط وقواعد البناء المتدفق (Intermediate Model & Pull Parser Rules)

1. **النموذج الوسيط (`Para` و`Table`)**:
   - `data class Para(val text: String)`
   - `data class TableCell(val paragraphs: List<Para>, val gridSpan: Int = 1, val vMerge: String? = null)`
   - `data class Table(val rows: List<List<TableCell>>)`
   - `sealed interface DocElement`:
     - `data class ParagraphElement(val para: Para) : DocElement`
     - `data class TableElement(val table: Table) : DocElement`

2. **حالات المكدّس ومعالجة الوسوم في `XmlPullParser`**:
   - **تجاهل الفروع غير المرغوبة (`skipDepth`)**:
     - عند الدخول في وسم محلي اسمه `"del"` أو `"delText"` أو `"instrText"` أو `"Fallback"` (داخل `"AlternateContent"`): يتم تفعيل عدّاد تخطٍّ (`ignoredElementDepth++`) لتجاهل كافة الأحداث النصية والفرعية حتى إغلاق الوسم، بينما يُسمح بالدخول الطبيعي في `"Choice"` و`"ins"`.
   - **بناء الفقرات وفواصل الأسطر (`p`, `r`, `rPr`, `u`, `t`, `tab`, `br`, `cr`, `noBreakHyphen`)**:
     - لكل فقرة `<p>` قائمة مقاطع سطرية (`mutableListOf<StringBuilder>()` تبدأ بمقطع واحد).
     - داخل `<r>`:
       - إذا ظهر `<rPr>` وفيه `<u ...>` حيث قيمة الخاصية `val` ليست `"none"` (أو بدون خاصية `val` مثل `<w:u/>`)، يُعلَم المقطع الحالي بأنه مسطّر (`isUnderlined = true`).
       - عند `<t>`: يُقرأ النص عبر `parser.nextText()` (أو أحداث `TEXT`) مباشرةً دون `decodeXmlEntities`؛ وإذا كان المقطع مسطّرًا وينتهي بنقطتين `:` يُغلّف بـ `<u>…</u> `.
       - عند `<tab>`: يُضاف `" "` إلى السطر الحالي في الفقرة.
       - عند `<noBreakHyphen>`: يُضاف `"-"` إلى السطر الحالي في الفقرة.
       - عند `<br>` أو `<cr>`: يُفتح سطر/فقرة فرعية جديدة بحيث تنتج الفقرة الواحدة عدة كائنات `Para(text)` داخل الخلية أو عدة أسطر خارج الجدول.
   - **الجداول المتداخلة (`tbl` داخل `tc`)**:
     - عند فتح `<tbl>`: يُدفع `TableBuilder` جديد في `tableStack`.
     - عند إغلاق `</tbl>`: يُسحب `TableBuilder` من `tableStack`:
       - إذا كان `tableStack` فارغًا (جدول من المستوى الأعلى): يُضاف كـ `TableElement(table)` إلى عناصر الوثيقة.
       - إذا كان `tableStack` غير فارغ (جدول متداخل داخل خلية أم): تُجمع فقرات جميع خلايا صفوف هذا الجدول المتداخل بترتيبها وتُضاف مباشرة إلى `cellStack.last().paragraphs` الخاصة بالخلية الأم.

3. **تحليل الجداول على النموذج الوسيط (`parseTableXml` و`extractCellParagraphs`)**:
   - تعمل `parseTableXml(table: Table): List<String>` و`extractCellParagraphs(cell: TableCell): List<String>` (إلى جانب `extractCellParagraphBlocks`) مباشرةً على كائنات `Table` و`TableCell` و`Para`.
   - تظل دوال تصنيف عناوين الأعمدة `classifyColumnHeader` و`resolveUnknownColumns` و`detectColumnsFromGrid` وصيغة الأسطر الناتجة (`[مرحلة: ...]`, `القدرات المستهدفة:`, `أنشطة المدرس:`, `المحتوى الديداكتيكي:`, `التقويم والدعم:`, `[نهاية الجدول]`, و`عنوان: عنصر - عنصر`) مطابقة تمامًا للسلوك الحالي.
