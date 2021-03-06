/*  GRAPHITE2 LICENSING

    Copyright 2010, SIL International
    All rights reserved.

    This library is free software; you can redistribute it and/or modify
    it under the terms of the GNU Lesser General Public License as published
    by the Free Software Foundation; either version 2.1 of License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should also have received a copy of the GNU Lesser General Public
    License along with this library in the file named "LICENSE".
    If not, write to the Free Software Foundation, 51 Franklin Street,
    Suite 500, Boston, MA 02110-1335, USA or visit their web page on the
    internet at http://www.fsf.org/licenses/lgpl.html.

Alternatively, the contents of this file may be used under the terms of the
Mozilla Public License (http://mozilla.org/MPL) or the GNU General Public
License, as published by the Free Software Foundation, either version 2
of the License or (at your option) any later version.
*/
#include <cassert>
#include "inc/Sparse.h"
#include "inc/bits.h"

using namespace graphite2;


sparse::~sparse() throw()
{
	free(m_array.values);
}


sparse::mapped_type sparse::operator [] (const key_type k) const throw()
{
	const chunk & 		c = m_array.map[k/SIZEOF_CHUNK];
	const mask_t 		m = c.mask >> (SIZEOF_CHUNK - 1 - (k%SIZEOF_CHUNK));
	const mapped_type   g = m & 1;

	return g*m_array.values[g*(c.offset + bit_set_count(m >> 1))];
}


size_t sparse::capacity() const throw()
{
	size_t n = m_nchunks,
		   s = 0;

	for (const chunk *ci=m_array.map; n; --n, ++ci)
		s += bit_set_count(ci->mask);

	return s;
}
