"""Explicit checks on this project's emulator only; never target a physical phone."""
from pathlib import Path
import subprocess, time, re, sys, xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
adb = root / '.toolchain/android-sdk/platform-tools/adb.exe'
serial = 'emulator-5554'

def command(*args):
    return subprocess.check_output([str(adb), '-s', serial, *args], text=True, encoding='utf-8').strip()

assert ('generic' in command('shell', 'getprop', 'ro.build.fingerprint')
        or 'sdk_gphone' in command('shell', 'getprop', 'ro.product.model')), 'Dedicated emulator required'

def ui():
    command('shell', 'uiautomator', 'dump', '/sdcard/dragon-farmer-test-ui.xml')
    return ET.fromstring(command('shell', 'cat', '/sdcard/dragon-farmer-test-ui.xml'))

def click(text=None, klass=None):
    for node in ui().iter('node'):
        if (text is None or text.casefold() in node.get('text', '').casefold()) and (klass is None or node.get('class') == klass):
            x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
            time.sleep(.5)  # Allow Accessibility to reconnect after UIAutomator exits.
            command('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(.4)
            return node.get('text','')
    raise AssertionError(f'UI target missing: {text or klass}')

mode=sys.argv[1]
if mode=='hardware':
    command('shell','settings','delete','secure','enabled_accessibility_services')
    time.sleep(.3)
    command('shell','am','force-stop','dev.s25.farmer')
    command('shell','am','start','-n','dev.s25.farmer/.MainActivity')
    time.sleep(1)
    # Inspect the button before enabling the service: UIAutomator itself can
    # suspend Accessibility, which would invalidate this hardware-path check.
    button=next(n for n in ui().iter('node') if 'Check recognition' in n.get('text',''))
    x1,y1,x2,y2=map(int,re.findall(r'\d+',button.get('bounds')))
    command('shell','settings','put','secure','enabled_accessibility_services','dev.s25.farmer/dev.s25.farmer.MacroService')
    command('shell','settings','put','secure','accessibility_enabled','1')
    time.sleep(1)
    command('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(1)
    command('emu','event','send','EV_KEY:KEY_VOLUMEDOWN:1','EV_SYN:0:0','EV_KEY:KEY_VOLUMEDOWN:0','EV_SYN:0:0')
    time.sleep(1)
    result=command('shell','run-as','dev.s25.farmer','cat','files/last-status.txt')
    assert result=='Stopped with Volume Down',result
    print('Virtual hardware Volume Down: PASS')
elif mode=='upgrade':
    command('uninstall','dev.s25.farmer')
    command('install',str(root/'dist/Dragon-Farmer.apk'))
    command('shell','am','start','-n','dev.s25.farmer/.MainActivity')
    time.sleep(1)
    click('Army & farming settings')
    click('Dragons in your army:')
    old=click(klass='android.widget.EditText')
    command('shell','input','keyevent','123')
    for _ in old: command('shell','input','keyevent','67')
    command('shell','input','text','17')
    click('Save')
    assert any(n.get('text')=='Dragons in your army: 17' for n in ui().iter('node'))
    command('install','-r',str(root/'native/app/build/outputs/apk/release/app-release.apk'))
    command('shell','am','start','-n','dev.s25.farmer/.MainActivity')
    time.sleep(1)
    click('Army & farming settings')
    assert any(n.get('text')=='Dragons in your army: 17' for n in ui().iter('node'))
    print('Signed release update preserves saved dragon count: PASS')
else:
    raise ValueError('Expected hardware or upgrade')
