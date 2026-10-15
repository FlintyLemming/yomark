"""在真机的 Gemini Nano 上量「AI 增强识别」的提示词：认出了几成、误打了几处、多久。

app 里的提示词原样量（androidTest 的 NanoPromptProbe 照 GeminiNanoClassifier 的做法送行、切段、解析，
问出厂设置下开着的那几类），这里只出题、打分，不另抄一份提示词。

题目：
  nano-pages-zh.json   55 页中文为主的截图文字（群聊、私聊、短信、购物物流、证件票据、技术页和几页英文），
                       每页有该打的（find，按 SensitiveKind 分类）、不该打的（avoid）、打不打都行的（neutral）。
                       由几个代理按类别分头写、另一个代理逐页核对；提示词里的例子不能用这里出现过的值。
  ner-bench-pages.json 70 页英文（--en），只有人名、地址、电话、邮箱四类真值。

用法（手机要是 debug 包，装好 app 和 androidTest 两个 APK：gradlew :app:installDebug :app:installDebugAndroidTest）：
  python nano_eval.py [--serial <adb 序列号>] [--en] [--tag 名字] [--examples N]
跑一轮几十分钟，要亮着屏（探针会把屏幕点亮、顶在最前面）。结果写到 out-nano/<名字>.jsonl，报告打到终端。
探针跑的就是 app 里的 GeminiNanoClassifier：答得比 20 秒慢的那段当没回答，chunks 里也就没有它。
"""
import argparse, json, os, subprocess, sys, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "moe.flinty.yomark"
# 出厂打码的那几类（RuleCatalog 的出厂态 + 聊天记录）：出厂关着的几类，真值里有也不算漏
DEFAULT_ON = {"PERSON_NAME", "PHONE", "POSTAL_ADDRESS", "PICKUP_CODE", "ID_NUMBER", "LICENSE_PLATE", "PAYMENT_CARD",
              "EMAIL", "BIRTH_DATE", "PASSPORT", "VIN", "API_KEY", "MAC_ADDR", "IBAN", "SSN", "CHAT_NAME", "CHAT_TITLE"}
NAMEISH = {"PERSON_NAME", "CHAT_NAME", "CHAT_TITLE"}


def load_pages(en):
    pages = []
    for p in json.load(open(os.path.join(HERE, "nano-pages-zh.json"), encoding="utf-8")):
        pages.append({"name": p["name"], "lines": [r["text"] for r in p["rows"]],
                      "find": [(f["kind"], f["text"]) for f in p["find"]], "avoid": p["avoid"], "neutral": p["neutral"]})
    if en:
        km = {"name": "PERSON_NAME", "address": "POSTAL_ADDRESS", "phone": "PHONE", "email": "EMAIL"}
        for g in json.load(open(os.path.join(HERE, "ner-bench-pages.json"), encoding="utf-8")):
            for p in g["pages"]:
                rows = sorted(p["rows"], key=lambda r: (r["y"], r["x"]))
                pages.append({"name": "en-" + p["name"], "lines": [r["text"] for r in rows],
                              "find": [(km[f["kind"]], f["text"]) for f in p["find"] if f["kind"] in km],
                              "avoid": p["avoid"],
                              "neutral": p["neutral"] + [f["text"] for f in p["find"] if f["kind"] not in km]})
    return pages


def run(pages, tag, adb):
    """把题目推上去、跑探针、拉回结果。"""
    os.makedirs(os.path.join(HERE, "out-nano"), exist_ok=True)
    local_in = os.path.join(HERE, "out-nano", f"{tag}-in.jsonl")
    with open(local_in, "w", encoding="utf-8", newline="\n") as f:
        for p in pages:
            f.write(json.dumps({"id": p["name"], "lines": p["lines"]}, ensure_ascii=False) + "\n")
    adb("push", local_in, f"/data/local/tmp/{tag}-in.jsonl")
    adb("shell", f"cat /data/local/tmp/{tag}-in.jsonl | run-as {PKG} sh -c 'mkdir -p files/nano-probe; cat > files/nano-probe/{tag}-in.jsonl'")
    # 上一轮的结果先删掉：探针一上来就死了的话，别把旧的拉回来当这一轮打分
    adb("shell", "run-as", PKG, "rm", "-f", f"files/nano-probe/{tag}-out.jsonl")
    r = adb("shell", "am", "instrument", "-w", "-e", "class", f"{PKG}.engine.genai.NanoPromptProbe", "-e", "nanoMode", "app",
            "-e", "nanoIn", f"{tag}-in.jsonl", "-e", "nanoOut", f"{tag}-out.jsonl", f"{PKG}.test/androidx.test.runner.AndroidJUnitRunner")
    log = r.decode("utf-8", "replace").strip()
    # am instrument 失败了也是退出码 0，只能看输出
    if "OK (" not in log:
        print(log, file=sys.stderr)
        sys.exit("probe did not finish; nothing scored")
    print(log[-300:], file=sys.stderr)
    data = adb("exec-out", "run-as", PKG, "cat", f"files/nano-probe/{tag}-out.jsonl")
    out = os.path.join(HERE, "out-nano", f"{tag}.jsonl")
    with open(out, "wb") as f:
        f.write(data)
    return out


def spans(lines, text):
    out = []
    for li, l in enumerate(lines):
        start = 0
        while text and (i := l.find(text, start)) >= 0:
            out.append((li, i, i + len(text)))
            start = i + 1
    return out


def overlaps(a, b):
    return a[0] == b[0] and min(a[2], b[2]) > max(a[1], b[1])


def covers(f, t):
    """找到的这一处盖住了真值的一半以上，自己又不比真值长太多（把整行都框了不算认出了这一处）。
    口径同 run_eval.hits。"""
    tl = t[2] - t[1]
    return (f[0] == t[0] and min(f[2], t[2]) - max(f[1], t[1]) >= 0.5 * tl
            and f[2] - f[1] <= max(2 * tl, tl + 6))


def score(pages, results, examples):
    truth_n = hit = kind_ok = found_n = fp = fp_avoid = neutral_n = loose_n = 0
    per_kind, fps, misses, ms, errors, slow, retried = {}, [], [], [], 0, 0, 0
    for p in pages:
        r = results.get(p["name"])
        if r is None:
            continue
        for c in r.get("chunks", []):
            if "error" in c:
                errors += 1
            elif "ms" in c:
                ms.append(c["ms"])
                slow += c["ms"] > 15_000                  # 离 app 的 20 秒超时不远了
                retried += "tries" in c
        fs = [(f["line"], f["start"], f["end"], f["kind"]) for f in r.get("findings", [])]
        truth = list(dict.fromkeys((sp, k) for k, t in p["find"] if k in DEFAULT_ON for sp in spans(p["lines"], t)))
        neutral = [sp for k, t in p["find"] if k not in DEFAULT_ON for sp in spans(p["lines"], t)]
        neutral += [sp for t in p["neutral"] for sp in spans(p["lines"], t)]
        avoid = [sp for t in p["avoid"] for sp in spans(p["lines"], t)]
        for sp, k in truth:
            pk = per_kind.setdefault(k, [0, 0])
            pk[0] += 1
            truth_n += 1
            got = [f for f in fs if covers(f, sp)]
            if got:
                hit += 1
                pk[1] += 1
                kind_ok += any(f[3] == k or (f[3] in NAMEISH and k in NAMEISH) for f in got)
            else:
                misses.append((p["name"], k, p["lines"][sp[0]][sp[1]:sp[2]]))
        for f in fs:
            found_n += 1
            if any(covers(f, sp) for sp, _ in truth):
                continue
            # 压到了真值却不算盖住（太长，比如框了整行；或只盖了一角）：不算命中，也不算误打，和 neutral 一样不进准确率
            if any(overlaps(f, sp) for sp, _ in truth):
                neutral_n += 1
                loose_n += 1
                continue
            if any(overlaps(f, sp) for sp in neutral):
                neutral_n += 1
                continue
            fp += 1
            fp_avoid += any(overlaps(f, sp) for sp in avoid)
            fps.append((p["name"], f[3], p["lines"][f[0]][f[1]:f[2]], p["lines"][f[0]]))
    judged = found_n - neutral_n
    print(f"pages {len(results)}  requests {len(ms) + errors} (errors {errors}, over 15s {slow}, "
          f"retried {retried})  median {statistics.median(ms) / 1000 if ms else 0:.1f}s per request")
    print(f"recall {hit}/{truth_n} = {hit / max(1, truth_n):.1%}   right kind {kind_ok}/{hit}   "
          f"precision {(judged - fp) / max(1, judged):.1%} ({fp} wrong, {fp_avoid} of them on things that must not be masked; "
          f"{loose_n} too long or too short on a truth span, not judged)")
    print("per kind: " + "  ".join(f"{k} {h}/{t}" for k, (t, h) in sorted(per_kind.items(), key=lambda x: -x[1][0])))
    for title, rows in (("wrong", fps), ("missed", misses)):
        if examples and rows:
            print(f"{title}:")
            for row in rows[:examples]:
                print("  ", row)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--en", action="store_true", help="also the 70 English pages")
    ap.add_argument("--tag", default="nano")
    ap.add_argument("--examples", type=int, default=0)
    ap.add_argument("--score-only", action="store_true", help="score out-nano/<tag>.jsonl again without running")
    a = ap.parse_args()
    exe = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    exe = exe if os.path.exists(exe) else "adb"

    def adb(*args):
        cmd = [exe] + (["-s", a.serial] if a.serial else []) + list(args)
        return subprocess.run(cmd, capture_output=True, check=True).stdout

    pages = load_pages(a.en)
    out = os.path.join(HERE, "out-nano", f"{a.tag}.jsonl") if a.score_only else run(pages, a.tag, adb)
    results, meta = {}, None
    for line in open(out, encoding="utf-8"):
        if line.strip():
            r = json.loads(line)
            if r["id"] == "_meta":
                meta = r
            else:
                results[r["id"]] = r
    sys.stdout.reconfigure(encoding="utf-8")
    print("model:", meta)
    score(pages, results, a.examples)


if __name__ == "__main__":
    main()
