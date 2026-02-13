module uart_top_apb (
      input  wire        reset
    , input  wire        clock
    , input  wire        in_psel
    , input  wire        in_penable
    , input  wire [ 2:0] in_pprot
    , output             in_pready
    , output wire        in_pslverr
    , input  wire [31:0] in_paddr
    , input  wire        in_pwrite
    , output wire [31:0] in_prdata
    , input  wire [31:0] in_pwdata
    , input  wire [ 3:0] in_pstrb
    , input  wire        uart_rx     // serial output
    , output wire        uart_tx     // serial input
);
  //--------------------------------------------------
  wire       ctsn = 1'b0;
  wire       dtr_pad_o;
  wire       dsr_pad_i = 1'b0;
  wire       ri_pad_i = 1'b0;
  wire       dcd_pad_i = 1'b0;
  wire       interrupt;
  //--------------------------------------------------------
  reg  [1:0] byte_offset; // FIXME: this should be wire, not reg,
  // however, it can not be compiled with verilator. `wire assigned with always-procedure`
  reg  [7:0] reg_dat8_w; // write to reg
  reg  [7:0] reg_dat8_w_reg;
  wire [7:0] reg_dat8_r; // read from reg
  //--------------------------------------------------------
  assign in_pready  = in_psel && in_penable;
  assign in_pslverr = 1'b0;
  wire reg_we     = ~reset & in_psel & ~in_penable & in_pwrite;
  wire reg_re     = ~reset & in_psel & ~in_penable & ~in_pwrite;
  assign in_prdata  = (in_psel) ? {4{reg_dat8_r}} : 'h0;
  // because of the waddr from axi4 and from cpu's LSU,
  // in_paddr should always be aligned to 4bytes, with in_paddr[1:0] always in 0.
  // we need to restore the original offset based on pstrb
  // and select the correct byte (data has been shifted to the correct position)
  always @(*) begin
    case (in_pstrb)
      4'b0001: begin byte_offset = 2'b00; reg_dat8_w = in_pwdata[7:0];   end
      4'b0010: begin byte_offset = 2'b01; reg_dat8_w = in_pwdata[15:8];  end
      4'b0100: begin byte_offset = 2'b10; reg_dat8_w = in_pwdata[23:16]; end
      4'b1000: begin byte_offset = 2'b11; reg_dat8_w = in_pwdata[31:24]; end
      default: begin byte_offset = 2'b00; reg_dat8_w = in_pwdata[7:0];   end
    endcase
  end
  // write: address is aligned to 4bytes, need to restore the original offset based on pstrb
  // read: address is not aligned, for narrow transfer, use the original address
  wire [2:0] reg_adr = in_pwrite ? {in_paddr[2], byte_offset} : in_paddr[2:0];
  always @(posedge clock) begin
    reg_dat8_w_reg <= reg_dat8_w;
  end
  //--------------------------------------------------------
  // Registers
  // As shown below reg_dat_i should be stable
  // one-cycle after reg_we negates.
  //              ___     ___     ___     ___     ___     ___
  //  clock    __|   |___|   |___|   |___|   |___|   |___|   |__
  //             ________________        ________________
  //  reg_adr  XX________________XXXXXXXX________________XXXX
  //             ________________
  //  reg_dat_i X________________XXXXXXX
  //                                     ________________
  //  reg_dat_o XXXXXXXXXXXXXXXXXXXXXXXXX________________XXXX
  //                                              _______
  //  reg_re   __________________________________|       |_____
  //              _______
  //  reg_we   __|       |_____________________________________
  //
  uart_regs Uregs (
      .clk         (clock),
      .wb_rst_i    (reset),
      .wb_addr_i   (reg_adr),
      .wb_dat_i    (in_pwrite ? reg_dat8_w : reg_dat8_w_reg),
      .wb_dat_o    (reg_dat8_r),
      .wb_we_i     (reg_we),
      .wb_re_i     (reg_re),
      .modem_inputs({~ctsn, dsr_pad_i, ri_pad_i, dcd_pad_i}),
      .stx_pad_o   (uart_tx),
      .srx_pad_i   (uart_rx),
      .rts_pad_o   ( ),
      .dtr_pad_o   (dtr_pad_o),
      .int_o       (interrupt)
  );
endmodule
