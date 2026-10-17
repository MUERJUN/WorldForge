import io

render = io.open('/tmp/render_section.java', encoding='utf-8', newline='').read()

# ---- adapt the v0.3.1 render section to the v0.3.2 state machine ----
render = render.replace('if (dirty && (mode == Mode.EDIT || mode == Mode.CREATE)) {',
                        'if (ui.dirty() && ui.editorVisible()) {')
render = render.replace('''					ui.markDirty();''', '''					ui.markDirty();''')
render = render.replace('''					dirty = true;''', '''					ui.markDirty();''')
render = render.replace('''					setMode(editorWasEditing ? Mode.EDIT : Mode.CREATE);''',
                        '''					closeModal();''')
render = render.replace('''				setMode(Mode.EDIT);
				return true;''', '''				closeModal();
				return true;''')

# sanity: nothing from the removed private fields may remain
for bad in ['discardArmed', 'closeArmed', 'applyVisibility', 'trySendOnEnter', 'aiPanel.hasPending']:
    assert bad not in render, "render section still references " + bad

p = 'src/client/java/dev/worldforge/client/gui/WorldForgeStudioScreen.java'
s = io.open(p, encoding='utf-8', newline='').read()

anchor = '\t// ---------- automation hooks (used by StudioAutoTest; no gameplay effect) ----------'
assert anchor in s, "anchor"

# the extracted block ends with the class-closing brace; strip it (we insert inside the class)
block = render.rstrip()
assert block.endswith('}'), "unexpected tail"
block = block[:-1].rstrip('\n') + '\n\n'

s = s.replace(anchor, block + anchor, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print("render section restored, inserted lines:", block.count('\n'))