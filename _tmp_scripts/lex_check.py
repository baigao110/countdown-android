# -*- coding: utf-8 -*-
p = 'app/src/main/java/com/baigao/countdown/UpdateManager.kt'
lines = open(p, 'rb').read().decode('utf-8').split('\n')

def klex(ln):
    state = 'code'
    opens = 0
    closes = 0
    i = 0
    while i < len(ln):
        ch = ln[i]
        if state == 'code':
            if ch == '"':
                state = 'str'
                opens += 1
        else:
            if ch == '\\':
                i += 1  # skip escaped char
            elif ch == '"':
                state = 'code'
                closes += 1
        i += 1
    return state, opens, closes

for label, idx in (('v169', 739), ('v168', 740)):
    ln = lines[idx]
    st, o, c = klex(ln)
    print('%s line: final=%s opens=%d closes=%d' % (label, st, o, c))
    if st != 'code':
        print('   >>> STRING NOT CLOSED')
    print('   start:', repr(ln[:30]))
    print('   end  :', repr(ln[-25:]))
