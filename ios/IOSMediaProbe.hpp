// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

#include <QString>

class IOSMediaProbe final {
public:
	IOSMediaProbe();
	~IOSMediaProbe();

	IOSMediaProbe(const IOSMediaProbe &) = delete;
	IOSMediaProbe &operator=(const IOSMediaProbe &) = delete;

	void start();
	void stop();
	QString status();

private:
	struct Impl;
	Impl *impl;
};
