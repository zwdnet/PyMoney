# -*- coding:utf-8 -*-

import sqlite3

# 数据库处理类 没有调通，暂时放弃
class DataBase(object):
    def __init__(self, Name):
        self.DatabaseName = Name
        self.con = sqlite3.connect(self.DatabaseName)
        self.cu = self.con.cursor()
        self.data = None
        
    def Execute(self, QueryCommand):
        self.cu.execute(QueryCommand)

    def GetConnect(self):
        return self.con

    def GetCursor(self):
        return self.cu
        
    def GetResult(self):
        self.data = self.cu.fetchall()
        return self.data
        
    def __del__(self):
        self.cu.close()
        self.con.close()


if __name__ == "__main__":
    db = DataBase("moneyold.db")
    query = "select name from sqlite_master where type='table'"
    db.Execute(query)
    result = db.GetResult()
    print(result)
    