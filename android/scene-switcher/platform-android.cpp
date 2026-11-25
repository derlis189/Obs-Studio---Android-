// SPDX-License-Identifier: GPL-2.0-or-later
#include "platform-funcs.hpp"
#include <obs-module.h>
namespace advss {
// Android sandboxes other applications. Desktop window/process automation is
// intentionally unavailable; macros based on OBS state continue to work.
std::vector<WindowInfo> GetWindows(const WindowQueryOptions &) { return {}; }
std::string GetCurrentWindowTitle() { return {}; }
int SecondsSinceLastInput() { return 0; }
QStringList GetProcessList() { return {}; }
std::string GetForegroundProcessName() { return {}; }
std::string GetForegroundProcessPath() { return {}; }
QStringList GetProcessPathsFromName(const QString &) { return {}; }
bool IsInFocus(const QString &) { return false; }
void PlatformInit() { blog(LOG_INFO, "[adv-ss] Android: OBS automation only; desktop hooks unavailable"); }
void PlatformCleanup() {}
}
