// SPDX-License-Identifier: GPL-2.0-or-later
#include "hotkey-helpers.hpp"
namespace advss {
bool CanSimulateKeyPresses() { return false; }
void PressKeys(const std::vector<HotkeyType> &, int) {}
}
