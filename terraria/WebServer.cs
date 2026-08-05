using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AynDualScreen
{
	internal sealed class HttpRequest
	{
		public string Method { get; set; }
		public string Path { get; set; }
		public string Query { get; set; }
		public string Body { get; set; }
	}

	internal sealed class HttpResponse
	{
		public int Status { get; set; } = 200;
		public string ContentType { get; set; } = "text/plain; charset=utf-8";
		public byte[] Body { get; set; } = Array.Empty<byte>();

		/// <summary>The <c>Cache-Control</c> header value. Snapshots must never be cached; item icons never change, so they should be.</summary>
		public string CacheControl { get; set; } = "no-store";

		public static HttpResponse Text(string body, string contentType = "text/plain; charset=utf-8")
		{
			return new HttpResponse { Body = Encoding.UTF8.GetBytes(body), ContentType = contentType };
		}

		public static HttpResponse Json(string json)
		{
			return Text(json, "application/json; charset=utf-8");
		}

		public static HttpResponse Bytes(byte[] body, string contentType)
		{
			return new HttpResponse { Body = body, ContentType = contentType };
		}

		public static HttpResponse NotFound()
		{
			return new HttpResponse { Status = 404, Body = Encoding.UTF8.GetBytes("not found") };
		}
	}

	/// <summary>
	/// A deliberately small HTTP/1.1 server built on <see cref="TcpListener"/>.
	/// <para>
	/// This avoids <see cref="HttpListener"/>, which needs a URL ACL reservation (admin rights) on Windows and isn't
	/// dependable on other runtimes. Every request is answered and the socket is closed; at ~10 polls/second over
	/// loopback that costs nothing.
	/// </para>
	/// <para>The handler runs on a thread pool thread, so it must only touch data that is safe to read off the game thread.</para>
	/// </summary>
	internal sealed class WebServer : IDisposable
	{
		private readonly TcpListener Listener;
		private readonly Func<HttpRequest, HttpResponse> Handler;
		private readonly Action<string> Log;
		private readonly CancellationTokenSource Cancellation = new();

		public WebServer(IPAddress address, int port, Func<HttpRequest, HttpResponse> handler, Action<string> log)
		{
			this.Listener = new TcpListener(address, port);
			this.Handler = handler;
			this.Log = log;
		}

		public void Start()
		{
			this.Listener.Start();
			Task.Run(this.AcceptLoop);
		}

		private async Task AcceptLoop()
		{
			while (!this.Cancellation.IsCancellationRequested)
			{
				TcpClient client;
				try
				{
					client = await this.Listener.AcceptTcpClientAsync().ConfigureAwait(false);
				}
				catch (ObjectDisposedException)
				{
					return; // stopped
				}
				catch (SocketException)
				{
					return;
				}

				_ = Task.Run(() => this.Serve(client));
			}
		}

		private void Serve(TcpClient client)
		{
			try
			{
				using (client)
				{
					client.NoDelay = true;
					client.ReceiveTimeout = 5000;
					client.SendTimeout = 5000;

					using NetworkStream stream = client.GetStream();

					string requestLine = ReadLine(stream);
					if (string.IsNullOrWhiteSpace(requestLine))
						return;

					string[] parts = requestLine.Split(' ');
					if (parts.Length < 2)
						return;

					int contentLength = 0;
					string header;
					while (!string.IsNullOrEmpty(header = ReadLine(stream)))
					{
						int colon = header.IndexOf(':');
						if (colon > 0 && header.Substring(0, colon).Trim().Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
							int.TryParse(header.Substring(colon + 1).Trim(), out contentLength);
					}

					string body = string.Empty;
					if (contentLength > 0 && contentLength <= 64 * 1024)
						body = ReadBody(stream, contentLength);

					string target = parts[1];
					int queryStart = target.IndexOf('?');

					var request = new HttpRequest
					{
						Method = parts[0],
						Path = queryStart >= 0 ? target.Substring(0, queryStart) : target,
						Query = queryStart >= 0 ? target.Substring(queryStart + 1) : string.Empty,
						Body = body
					};

					HttpResponse response;
					try
					{
						response = this.Handler(request) ?? HttpResponse.NotFound();
					}
					catch (Exception ex)
					{
						this.Log($"Error handling {request.Method} {request.Path}: {ex}");
						response = new HttpResponse { Status = 500, Body = Encoding.UTF8.GetBytes("internal error") };
					}

					Write(stream, response);
				}
			}
			catch (IOException)
			{
				// client hung up mid-request; nothing to do
			}
			catch (SocketException)
			{
			}
			catch (Exception ex)
			{
				this.Log($"Connection error: {ex.Message}");
			}
		}

		/// <summary>Read one CRLF-terminated line. Reads a byte at a time so the body bytes are left untouched in the stream.</summary>
		private static string ReadLine(Stream stream)
		{
			var buffer = new StringBuilder(128);
			while (true)
			{
				int b = stream.ReadByte();
				if (b < 0)
					break;
				if (b == '\n')
					break;
				if (b != '\r')
					buffer.Append((char)b);

				if (buffer.Length > 8192)
					break; // refuse absurd headers
			}

			return buffer.ToString();
		}

		private static string ReadBody(Stream stream, int contentLength)
		{
			var buffer = new byte[contentLength];
			int read = 0;
			while (read < contentLength)
			{
				int count = stream.Read(buffer, read, contentLength - read);
				if (count <= 0)
					break;
				read += count;
			}

			return Encoding.UTF8.GetString(buffer, 0, read);
		}

		private static void Write(Stream stream, HttpResponse response)
		{
			var head = new StringBuilder(256);
			head.Append("HTTP/1.1 ").Append(response.Status).Append(' ').Append(StatusText(response.Status)).Append("\r\n");
			head.Append("Content-Type: ").Append(response.ContentType).Append("\r\n");
			head.Append("Content-Length: ").Append(response.Body.Length).Append("\r\n");
			head.Append("Cache-Control: ").Append(response.CacheControl).Append("\r\n");
			head.Append("Access-Control-Allow-Origin: *\r\n");
			head.Append("Access-Control-Allow-Headers: Content-Type\r\n");
			head.Append("Connection: close\r\n\r\n");

			byte[] headBytes = Encoding.ASCII.GetBytes(head.ToString());
			stream.Write(headBytes, 0, headBytes.Length);
			if (response.Body.Length > 0)
				stream.Write(response.Body, 0, response.Body.Length);
			stream.Flush();
		}

		private static string StatusText(int status)
		{
			return status switch
			{
				200 => "OK",
				400 => "Bad Request",
				404 => "Not Found",
				500 => "Internal Server Error",
				_ => "OK"
			};
		}

		public void Dispose()
		{
			this.Cancellation.Cancel();
			try
			{
				this.Listener.Stop();
			}
			catch
			{
				// already stopped
			}
			this.Cancellation.Dispose();
		}
	}
}
