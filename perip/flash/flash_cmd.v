import "DPI-C" function void flash_read(input int addr, output int data);

module flash_cmd(
  input             clock,
  input             valid,
  input       [7:0] cmd,
  input      [31:0] addr,
  output reg [31:0] data
);
  always@(negedge clock) begin
    if (valid)
      if (cmd == 8'h03) begin
        flash_read(addr, data);
      end else begin
        $fwrite(32'h80000002, "Assertion failed: Unsupport command `%xh`, only support `03h` read command\n", cmd);
        $fatal;
      end
  end
endmodule
