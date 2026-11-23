#!/system/bin/sh

ui_print "- Bootanimation auto-path fix (skye)"

SRC="$MODPATH/system/product/media/bootanimation.zip"
TMP="$MODPATH/.bootanimation.zip"

if [ ! -f "$SRC" ]; then
  ui_print "! bootanimation.zip missing from package, abort"
  abort "Broken package"
fi

TARGET=""
for p in \
  /system/media \
  /system/product/media \
  /product/media \
  /system_ext/media \
  /odm/media \
  /vendor/media \
  /system/system/media \
  /system/system/product/media
do
  if [ -f "$p/bootanimation.zip" ]; then
    TARGET="$p"
    break
  fi
done

if [ -z "$TARGET" ]; then
  TARGET="/system/product/media"
  ui_print "- No existing bootanimation detected, defaulting to $TARGET"
else
  ui_print "- Existing bootanimation found at $TARGET"
fi

REL="${TARGET#/}"
case "$REL" in
  system/system/*) REL="system/${REL#system/system/}" ;;
esac

mv -f "$SRC" "$TMP"
rm -rf "$MODPATH/system"
mkdir -p "$MODPATH/$REL"
mv -f "$TMP" "$MODPATH/$REL/bootanimation.zip"

ui_print "- Bootanimation will overlay /$REL/bootanimation.zip"
ui_print "- Done"
