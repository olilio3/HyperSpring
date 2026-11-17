"""zipalign mínimo: copia un zip dejando cada entrada sin comprimir alineada a 4 bytes
(4096 para las .so), rellenando el campo "extra" de su cabecera local."""
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, 'w') as zout:
    for info in zin.infolist():
        data = zin.read(info)
        ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        ni.compress_type = info.compress_type
        ni.external_attr = info.external_attr
        ni.extra = b''
        if info.compress_type == zipfile.ZIP_STORED:
            align = 4096 if info.filename.endswith('.so') else 4
            start = zout.fp.tell() + 30 + len(info.filename.encode('utf-8'))
            pad = (-start) % align
            ni.extra = b'\0' * pad
        zout.writestr(ni, data)
