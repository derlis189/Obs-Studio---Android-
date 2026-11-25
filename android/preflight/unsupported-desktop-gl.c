/* Diagnostic reproducing a gl-stagesurf.c call with Android GLES headers. */
#include <GLES3/gl3.h>
void desktop_readback(void) { glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0); }
