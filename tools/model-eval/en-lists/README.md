# 英文规则原型 v3 用的名单

`EnProto.kt`（`EnRules` 的第五个参数给 `v3`）读这些文件。`download_en_eval.sh` 把它们拷进 `names/`，与普查局的 `first.txt`、`last.txt` 放在一起。
都是从公开数据整理出来的纯文本，一行一项。

| 文件 | 条数 | 内容 | 来源与许可 |
|---|---|---|---|
| `ssa_first.txt` | 20000 | 美国 1950–2025 年出生人口里最常见的 2 万个名，首字母大写 | 美国社会保障局（SSA）全国新生儿名字数据，美国政府作品，公有领域 |
| `ambiguous_first.txt` | 1158 | 既是常见名、又是普通英文词的（Will、May、Mark、Grant、Hope……） | `ssa_first.txt` × WordNet 3.0 的小写词条；WordNet 的许可见 `LICENSE-wordnet.txt` |
| `ambiguous_last.txt` | 2732 | 既是常见姓、又是普通英文词的（Long、White、Young、King、Park……） | 普查局 2010 年姓氏前 3 万（公有领域）× WordNet 3.0 |
| `brands_nsi.txt` | 16419 | 连锁品牌、店名（`brand`、`name`、`brand:en`、`name:en`，只收拉丁字母的） | OpenStreetMap name-suggestion-index 8.0.20260918 的 `dist/json/nsi.min.json` 里所有 `brands/*`；BSD 3-Clause，见 `LICENSE-name-suggestion-index.md` |
| `usps_suffixes.txt` | 549 | 街道后缀的全部写法（AVENUE、AVE、AV……） | USPS Publication 28 附录 C1，美国政府作品 |
| `usps_units.txt` | 40 | 房号用词（APT、SUITE、FL、BLDG……） | USPS Publication 28 附录 C2 |

用法：两个词都在 `ambiguous_*` 里的「名 + 姓」不当人名（「Will Park」），单个的模糊名后面要跟一个姓才算；
整段正好是品牌名的不当猜出的人名（「Calvin Klein」「Tommy Hilfiger」这类以人名命名的品牌）。
`summarize_bench.py`、`score_en_rules.py` 的 `--veto-brands` 也用 `brands_nsi.txt` 过滤模型的结果。

## app 里的那一份

出厂规则（`EnglishLists`、`EnglishNameCues`、`EnglishNameGuess`、`EnglishAddressShape`）读的是
`app/src/main/resources/moe/flinty/yomark/rules/en/`，内容来自这里和普查局的名单：

- `first_names.txt`（20420 条）= `download_en_eval.sh` 生成的 `names/first.txt`（普查局 1990 年常见名）∪ 这里的 `ssa_first.txt`；
- `last_names.txt`（30000 条）= `names/last.txt`（普查局 2010 年姓氏前 3 万）；
- `brands.txt` 就是这里的 `brands_nsi.txt`，改了个名；两份许可文件也一起拷了过去，随包发行。

改这里的名单时，app 那份一起改，再跑 `en-rules`（不带名单目录，就是出厂规则）对照分数。
