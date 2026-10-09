# -*- coding: utf-8 -*-
import io

p = 'app/src/main/java/com/baigao/countdown/UpdateManager.kt'
text = open(p, 'rb').read().decode('utf-8')
# normalize to LF for safe editing, then rejoin with CRLF at the end
lines = text.replace('\r\n', '\n').split('\n')

v169 = ('ChangelogItem("v1.0.0.38", "2026-10-09", '
        '"v169：把「是否循环」这一格从「显示」和「落盘」两头一起钉死 —— '
        '一、显示侧：这一格的「被用户动过」改用「真在它上面抬手（ACTION_UP）」才算，'
        '滑一下取消的假触碰不认；打开编辑页后连发六记延迟复核'
        '（0 / 120 / 300 / 600 / 1000 / 1500 毫秒），盯住这一格一旦被更晚一帧冲回第 0 项「否」'
        '就当场按这条自己存的属性钉回去，存「是（归零重新计时）」稳稳停在「是」、'
        '存「否（归零就停住）」稳稳停在「否」；'
        '二、落盘侧：保存时不再看这一格「此刻显示什么」，而是以「这条自己存着的循环属性」为准 —— '
        '用户没动过这颗下拉框就一律沿用它原来存的 loop'
        '（存「是」就还是「是」、存「否」就还是「否」，绝不会被写坏成「否」），'
        '只有用户亲手动过（在它上面抬手点过）才以他挑的那一格为准。'
        '这就把前几轮「明明存的『是』、打开甚至保存后都变成『否』」的根子从两头堵死了。'
        '挑「是」的照样归零自己接着跑下一轮，悬浮窗同步照旧，老数据一行没动"),')

# line 740 is index 739
lines[739] = v169

# sanity checks
assert lines[739].count('"') == 6, lines[739].count('"')
assert lines[739].endswith('"),'), repr(lines[739][-8:])

# write back with CRLF
out = '\r\n'.join(lines)
open(p, 'w', encoding='utf-8', newline='').write(out)
print('OK line740 endswith:', repr(lines[739][-10:]))
print('quote count', lines[739].count('"'))
