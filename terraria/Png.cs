using System;
using System.IO;
using System.IO.Compression;

namespace AynDualScreen
{
	/// <summary>
	/// A minimal 8-bit RGBA PNG encoder.
	/// </summary>
	/// <remarks>
	/// The minimap is built from <c>Main.Map</c>, which lives in ordinary memory, so nothing about it needs the GPU.
	/// Encoding it here rather than round-tripping through a <c>Texture2D</c> keeps the whole snapshot off the graphics
	/// device — which matters because it runs every few frames on the game thread. The same encoder handles item and
	/// buff icons after their pixels have been read back.
	/// </remarks>
	internal static class Png
	{
		private static readonly uint[] CrcTable = BuildCrcTable();

		/// <summary>Encode straight RGBA bytes (4 per pixel, top row first) as a PNG.</summary>
		public static byte[] Encode(byte[] rgba, int width, int height)
		{
			if (width <= 0 || height <= 0 || rgba.Length < width * height * 4)
				return Array.Empty<byte>();

			using var output = new MemoryStream(rgba.Length / 4);

			// signature
			output.Write(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }, 0, 8);

			// IHDR: 8-bit truecolour with alpha, no interlacing
			var header = new byte[13];
			WriteInt(header, 0, width);
			WriteInt(header, 4, height);
			header[8] = 8;  // bit depth
			header[9] = 6;  // colour type: RGBA
			header[10] = 0; // deflate
			header[11] = 0; // adaptive filtering
			header[12] = 0; // no interlace
			WriteChunk(output, "IHDR", header);

			WriteChunk(output, "IDAT", Compress(rgba, width, height));
			WriteChunk(output, "IEND", Array.Empty<byte>());

			return output.ToArray();
		}

		/// <summary>Prefix each scanline with filter type 0 and wrap the result in a zlib stream.</summary>
		private static byte[] Compress(byte[] rgba, int width, int height)
		{
			int stride = width * 4;
			var raw = new byte[(stride + 1) * height];
			for (int y = 0; y < height; y++)
			{
				raw[y * (stride + 1)] = 0; // filter: none
				Buffer.BlockCopy(rgba, y * stride, raw, y * (stride + 1) + 1, stride);
			}

			using var zlib = new MemoryStream();
			zlib.WriteByte(0x78); // zlib header: deflate, 32K window
			zlib.WriteByte(0x01); // fastest compression

			using (var deflate = new DeflateStream(zlib, CompressionLevel.Fastest, leaveOpen: true))
				deflate.Write(raw, 0, raw.Length);

			uint adler = Adler32(raw);
			zlib.WriteByte((byte)(adler >> 24));
			zlib.WriteByte((byte)(adler >> 16));
			zlib.WriteByte((byte)(adler >> 8));
			zlib.WriteByte((byte)adler);

			return zlib.ToArray();
		}

		private static void WriteChunk(Stream stream, string type, byte[] data)
		{
			var length = new byte[4];
			WriteInt(length, 0, data.Length);
			stream.Write(length, 0, 4);

			var body = new byte[4 + data.Length];
			for (int i = 0; i < 4; i++)
				body[i] = (byte)type[i];
			Buffer.BlockCopy(data, 0, body, 4, data.Length);
			stream.Write(body, 0, body.Length);

			var crc = new byte[4];
			WriteInt(crc, 0, (int)Crc32(body));
			stream.Write(crc, 0, 4);
		}

		private static void WriteInt(byte[] buffer, int offset, int value)
		{
			buffer[offset] = (byte)(value >> 24);
			buffer[offset + 1] = (byte)(value >> 16);
			buffer[offset + 2] = (byte)(value >> 8);
			buffer[offset + 3] = (byte)value;
		}

		private static uint[] BuildCrcTable()
		{
			var table = new uint[256];
			for (uint i = 0; i < 256; i++)
			{
				uint c = i;
				for (int k = 0; k < 8; k++)
					c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
				table[i] = c;
			}
			return table;
		}

		private static uint Crc32(byte[] data)
		{
			uint c = 0xFFFFFFFFu;
			foreach (byte b in data)
				c = CrcTable[(c ^ b) & 0xFF] ^ (c >> 8);
			return c ^ 0xFFFFFFFFu;
		}

		private static uint Adler32(byte[] data)
		{
			uint a = 1, b = 0;
			foreach (byte value in data)
			{
				a = (a + value) % 65521;
				b = (b + a) % 65521;
			}
			return (b << 16) | a;
		}
	}
}
