<pre>
These Windows binaries were built using MinGW on Ubuntu 20.04
and the instructions on http://dfu-util.sourceforge.net/build.html

The libusb source was from latest git 2024-04-16
commit v1.0.27-11-g43107c84 and includes
43107c84 darwin: Suppress false positive warning with an assert

The dfu-util source was from latest git 2024-04-16
commit v0.11-17-gbe49612 and includes
be49612 dfuse: Allow direct transition to DNLOAD_IDLE on special command

The lsusb.exe utility was built from usbutils commit v014
with the patch lsusb_build_on_mingw.patch applied.

</pre>
